package me.chrisbanes.verity.mcp

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isInstanceOf
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession
import me.chrisbanes.verity.device.FocusChangeObserver

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VerityMcpFocusChangeTest {
  private fun tree(id: String? = "a") = HierarchyNode(attributes = id?.let { mapOf("resource-id" to it) }.orEmpty(), states = if (id == null) emptySet() else setOf("focused"))
  private class Script : DeviceSession by FakeDeviceSession() {
    val events = mutableListOf<String>()
    val budgets = mutableListOf<Duration>()
    var active = 0
    var peak = 0
    var animationWaits = 0
    var captures = 0
    var flowResult = FlowResult(true, "flow output")
    var read: suspend (Int) -> HierarchyNode = { HierarchyNode(attributes = mapOf("resource-id" to if (it == 0) "a" else "b"), states = setOf("focused")) }
    var action: suspend () -> Unit = {}
    override suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode {
      events += "capture"
      budgets += timeout
      active++
      peak = maxOf(peak, active)
      try {
        return read(captures++)
      } finally {
        active--
      }
    }
    override suspend fun executeFlow(yaml: String): FlowResult {
      events += "flow:$yaml"
      action()
      return flowResult
    }
    override suspend fun pressKey(keyName: String) {
      events += "key:$keyName"
      action()
    }
    override suspend fun waitForAnimationToEnd() {
      animationWaits++
    }
  }
  private data class Fixture(val server: Server, val id: String, val script: Script) {
    fun args(name: String, await: JsonElement? = JsonPrimitive(true), timeout: JsonElement? = null) = buildJsonObject {
      put("session_id", id)
      put(if (name == "run_flow") "yaml" else "key", if (name == "run_flow") "supplied YAML" else "BACK")
      if (await != null) put("await_focus_change", await)
      if (timeout != null) put("focus_timeout_ms", timeout)
    }
  }
  private suspend fun TestScope.fixture(script: Script = Script()): Fixture {
    val manager = McpDeviceSessionManager { _, _, _ -> script }
    val id = manager.open(Platform.ANDROID_TV).sessionId.toString()
    return Fixture(VerityMcpServer(manager, focusObserver = FocusChangeObserver { testScheduler.currentTime * 1_000_000 }).create(), id, script)
  }
  private suspend fun Server.call(name: String, args: JsonObject): CallToolResult = tools.getValue(name).handler.invoke(StubClientConnection(), CallToolRequest(CallToolRequestParams(name, args)))
  private fun CallToolResult.json() = Json.parseToJsonElement((content.single() as TextContent).text).jsonObject
  private val names = listOf("press_key", "run_flow")

  @Test fun `both schemas specify strict focus options without changing catalog`() {
    val server = VerityMcpServer().create()
    assertThat(server.tools.size).isEqualTo(14)
    for (name in names) {
      val properties = server.tools.getValue(name).tool.inputSchema.properties!!
      val await = properties.getValue("await_focus_change").jsonObject
      assertThat(await.getValue("type").jsonPrimitive.content).isEqualTo("boolean")
      assertThat(await.getValue("default").jsonPrimitive.boolean).isEqualTo(false)
      val timeout = properties.getValue("focus_timeout_ms").jsonObject
      assertThat(timeout.getValue("type").jsonPrimitive.content).isEqualTo("integer")
      assertThat(timeout.getValue("minimum").jsonPrimitive.int).isEqualTo(1)
      assertThat(timeout.getValue("default").jsonPrimitive.int).isEqualTo(2000)
      assertThat(timeout.getValue("maximum").jsonPrimitive.int).isEqualTo(Int.MAX_VALUE)
    }
  }

  @Test fun `both tools preserve action output and complete focus evidence`() = runTest {
    for (name in names) {
      val f = fixture()
      val response = f.server.call(name, f.args(name))
      val json = response.json()
      assertThat(response.isError).isEqualTo(false)
      assertThat(json.keys).isEqualTo(setOf("action", "focus_changed", "timed_out", "elapsed_ms", "focus_before", "focus_after", "focus_error"))
      assertThat(json.getValue("action").jsonObject.getValue("status").jsonPrimitive.content).isEqualTo("succeeded")
      assertThat(json.getValue("action").jsonObject.getValue("output").jsonPrimitive.content).isEqualTo(if (name == "run_flow") "flow output" else "Pressed key: BACK")
      assertThat(json.getValue("focus_changed").jsonPrimitive.boolean).isEqualTo(true)
      assertThat(json.getValue("timed_out").jsonPrimitive.boolean).isEqualTo(false)
      assertThat(json.getValue("elapsed_ms").jsonPrimitive.long).isEqualTo(0)
      assertThat(json.getValue("focus_before").jsonArray.single().jsonObject).isEqualTo(
        buildJsonObject {
          put("path", "/")
          put("resource_id", "a")
        },
      )
      assertThat(json.getValue("focus_after").jsonArray.single().jsonObject.getValue("resource_id").jsonPrimitive.content).isEqualTo("b")
      assertThat(json.getValue("focus_error")).isEqualTo(JsonNull)
      assertThat(f.script.events).isEqualTo(listOf("capture", if (name == "run_flow") "flow:supplied YAML" else "key:BACK", "capture"))
      assertThat(f.script.animationWaits).isEqualTo(0)
      assertThat(f.script.budgets.first().inWholeMilliseconds).isEqualTo(2000)
      assertThat(f.script.peak).isEqualTo(1)
      assertThat(f.script.active).isEqualTo(0)
    }
  }

  @Test fun `both tools distinguish delayed absent lost empty and unrelated focus`() = runTest {
    for (name in names) {
      for ((before, after, changed) in listOf(Triple("a", "b", true), Triple(null, "b", true), Triple("a", null, true), Triple(null, null, false), Triple("a", "a", false))) {
        val script = Script().apply { read = { if (it < 2) tree(before) else tree(after).copy(attributes = tree(after).attributes + ("text" to "different")) } }
        val f = fixture(script)
        val response = f.server.call(name, f.args(name, timeout = JsonPrimitive(250)))
        val json = response.json()
        assertThat(response.isError).isEqualTo(false)
        assertThat(json.getValue("focus_changed").jsonPrimitive.boolean).isEqualTo(changed)
        assertThat(json.getValue("timed_out").jsonPrimitive.boolean).isEqualTo(!changed)
        assertThat(json.getValue("elapsed_ms").jsonPrimitive.long).isEqualTo(if (changed) 100 else 250)
        if (after == null) assertThat(json.getValue("focus_after").jsonArray.size).isEqualTo(0)
        assertThat(script.animationWaits).isEqualTo(0)
        assertThat(script.events.count { it.startsWith("flow:") || it.startsWith("key:") }).isEqualTo(1)
      }
    }
  }

  @Test fun `whole flow returning to baseline produces no intermediate observation`() = runTest {
    var focus = "a"
    val script = Script().apply {
      read = { tree(focus) }
      action = {
        focus = "b"
        focus = "a"
      }
    }
    val f = fixture(script)
    val json = f.server.call("run_flow", f.args("run_flow", timeout = JsonPrimitive(1))).json()
    assertThat(json.getValue("focus_changed").jsonPrimitive.boolean).isEqualTo(false)
    assertThat(json.getValue("timed_out").jsonPrimitive.boolean).isEqualTo(true)
    assertThat(script.events.count { it.startsWith("flow:") }).isEqualTo(1)
  }

  @Test fun `strict invalid options prevent all device calls and inactive timeouts are ignored`() = runTest {
    val invalidAwait = listOf(JsonNull, JsonPrimitive("true"), JsonPrimitive(1), buildJsonArray {})
    val invalidTimeout = listOf(JsonNull, JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive("1"), JsonPrimitive(true), JsonPrimitive(2147483648L), buildJsonObject {})
    for (name in names) {
      val f = fixture()
      for (value in invalidAwait) assertThat(f.server.call(name, f.args(name, await = value)).isError).isEqualTo(true)
      for (value in invalidTimeout) assertThat(f.server.call(name, f.args(name, timeout = value)).isError).isEqualTo(true)
      assertThat(f.script.events).isEqualTo(emptyList())
      for (await in listOf(null, JsonPrimitive(false))) {
        for (value in invalidTimeout) {
          val response = f.server.call(name, f.args(name, await = await, timeout = value))
          assertThat(response.isError).isIn(null, false)
          assertThat((response.content.single() as TextContent).text).isEqualTo(if (name == "run_flow") "SUCCESS" else "Pressed key: BACK")
        }
      }
      assertThat(f.script.captures).isEqualTo(0)
      assertThat(f.script.animationWaits).isEqualTo(if (name == "press_key") invalidTimeout.size * 2 else 0)
      f.script.flowResult = FlowResult(false, "original failure")
      if (name == "run_flow") assertThat((f.server.call(name, f.args(name, await = JsonPrimitive(false))).content.single() as TextContent).text).isEqualTo("FAILED: original failure")
    }
  }

  @Test fun `failed action baseline failure and post capture failure have separate results`() = runTest {
    for (name in names) {
      val action = Script().apply { if (name == "run_flow") flowResult = FlowResult(false, "failed flow") else this.action = { error("failed key") } }
      val f = fixture(action)
      val response = f.server.call(name, f.args(name))
      val json = response.json()
      assertThat(response.isError).isEqualTo(true)
      assertThat(json.getValue("action").jsonObject.getValue("status").jsonPrimitive.content).isEqualTo("failed")
      assertThat(json.getValue("action").jsonObject.getValue("output").jsonPrimitive.content).isEqualTo(if (name == "run_flow") "failed flow" else "failed key")
      assertThat(json.getValue("focus_after")).isEqualTo(JsonNull)
      assertThat(json.getValue("focus_error")).isEqualTo(JsonNull)
      assertThat(json.getValue("elapsed_ms").jsonPrimitive.long).isEqualTo(0)
      assertThat(action.captures).isEqualTo(1)
      for (timedOut in listOf(false, true)) {
        val baseline = Script().apply {
          read = {
            if (timedOut) {
              delay(300)
              tree()
            } else {
              error("baseline")
            }
          }
        }
        val bf = fixture(baseline)
        val result = bf.server.call(name, bf.args(name, timeout = JsonPrimitive(200)))
        val bj = result.json()
        assertThat(result.isError).isEqualTo(true)
        assertThat(bj.getValue("action").jsonObject.getValue("status").jsonPrimitive.content).isEqualTo("not_executed")
        assertThat(bj.getValue("focus_before")).isEqualTo(JsonNull)
        assertThat(bj.getValue("focus_after")).isEqualTo(JsonNull)
        assertThat(bj.getValue("timed_out").jsonPrimitive.boolean).isEqualTo(false)
        assertThat(bj.getValue("elapsed_ms").jsonPrimitive.long).isEqualTo(0)
        assertThat(bj.getValue("focus_error").jsonObject.getValue("code").jsonPrimitive.content).isEqualTo(if (timedOut) "baseline_capture_timed_out" else "baseline_capture_failed")
        assertThat(baseline.events).isEqualTo(listOf("capture"))
        assertThat(baseline.active).isEqualTo(0)
      }
      val post = Script().apply { read = { if (it < 2) tree() else error("post capture") } }
      val pf = fixture(post)
      val pr = pf.server.call(name, pf.args(name))
      val pj = pr.json()
      assertThat(pr.isError).isEqualTo(true)
      assertThat(pj.getValue("focus_error").jsonObject.getValue("code").jsonPrimitive.content).isEqualTo("focus_capture_failed")
      assertThat(pj.getValue("focus_after").jsonArray.size).isEqualTo(1)
      assertThat(pj.getValue("timed_out").jsonPrimitive.boolean).isEqualTo(false)
      assertThat(pj.getValue("elapsed_ms").jsonPrimitive.long).isEqualTo(100)
    }
  }

  @Test fun `slow post capture owns timeout and returns unknown after complete exit`() = runTest {
    for (name in names) {
      val script = Script().apply {
        read = {
          if (it == 0) {
            tree()
          } else {
            delay(300)
            tree("b")
          }
        }
      }
      val f = fixture(script)
      val json = f.server.call(name, f.args(name, timeout = JsonPrimitive(200))).json()
      assertThat(json.getValue("timed_out").jsonPrimitive.boolean).isEqualTo(true)
      assertThat(json.getValue("elapsed_ms").jsonPrimitive.long).isEqualTo(200)
      assertThat(json.getValue("focus_after")).isEqualTo(JsonNull)
      assertThat(script.active).isEqualTo(0)
    }
  }

  @Test fun `caller and foreign cancellation at every boundary propagates`() = runTest {
    for (name in names) {
      for (boundary in listOf("baseline", "action", "capture", "delay", "wrapped-action", "wrapped-capture")) {
        val entered = CompletableDeferred<Unit>()
        var caller: kotlinx.coroutines.Job? = null
        val script = Script().apply {
          read = { index ->
            if ((boundary == "baseline" && index == 0) || (boundary == "capture" && index == 1)) {
              entered.complete(Unit)
              awaitCancellation()
            }
            if (boundary == "wrapped-capture" && index == 1) {
              caller!!.cancel()
              error("SDK wrapped interruption")
            }
            tree()
          }
          action = {
            if (boundary == "action") {
              entered.complete(Unit)
              awaitCancellation()
            }
            if (boundary == "wrapped-action") {
              caller!!.cancel()
              error("SDK wrapped interruption")
            }
          }
        }
        val f = fixture(script)
        val job = async {
          caller = currentCoroutineContext()[kotlinx.coroutines.Job]
          f.server.call(name, f.args(name))
        }
        if (boundary.startsWith("wrapped")) {
          assertFailure { job.await() }.isInstanceOf<CancellationException>()
        } else {
          if (boundary == "delay") runCurrent() else entered.await()
          job.cancel()
          assertFailure { job.await() }.isInstanceOf<CancellationException>()
        }
        assertThat(script.active).isEqualTo(0)
      }
      for (baseline in listOf(true, false)) {
        val script = Script().apply {
          read = {
            if ((it == 0) == baseline) {
              withTimeout(1) {
                delay(2)
                tree()
              }
            } else {
              tree()
            }
          }
        }
        val f = fixture(script)
        assertFailure { f.server.call(name, f.args(name)) }.isInstanceOf<CancellationException>()
      }
    }
  }

  @Test fun `session lock contains baseline action and all observations`() = runTest {
    for (name in names) {
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      var first = true
      val script = Script().apply {
        action = {
          if (first) {
            first = false
            entered.complete(Unit)
            release.await()
          }
        }
      }
      val f = fixture(script)
      val firstCall = async { f.server.call(name, f.args(name)) }
      entered.await()
      val queued = async { f.server.call(name, f.args(name, await = JsonPrimitive(false))) }
      runCurrent()
      assertThat(script.events.size).isEqualTo(2)
      release.complete(Unit)
      firstCall.await()
      queued.await()
      assertThat(script.events).isEqualTo(listOf("capture", if (name == "run_flow") "flow:supplied YAML" else "key:BACK", "capture", if (name == "run_flow") "flow:supplied YAML" else "key:BACK"))
    }
  }
}
