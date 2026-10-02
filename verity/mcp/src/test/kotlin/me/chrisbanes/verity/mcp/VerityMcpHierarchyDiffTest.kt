package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isTrue
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession

class VerityMcpHierarchyDiffTest {
  private class Fixture(
    val fake: FakeDeviceSession,
    val manager: McpDeviceSessionManager,
    val store: McpHierarchySnapshotStore,
    val sessionId: UUID,
    val server: Server,
  ) {
    fun arguments(before: UUID? = null, after: UUID? = null) = buildJsonObject {
      put("session_id", sessionId.toString())
      before?.let { put("before_snapshot_id", it.toString()) }
      after?.let { put("after_snapshot_id", it.toString()) }
    }
  }

  private suspend fun fixture(
    renderer: suspend (UUID, ResolvedHierarchySnapshotPair) -> String = { id, pair -> HierarchyDiff.render(id, pair) },
  ): Fixture {
    val fake = FakeDeviceSession()
    val device = object : DeviceSession by fake {
      override suspend fun captureHierarchyTree(): HierarchyNode = error("diff must not capture a device tree")
    }
    val manager = McpDeviceSessionManager { _, _, _ -> device }
    val store = McpHierarchySnapshotStore()
    val handle = manager.open(Platform.ANDROID_TV)
    return Fixture(fake, manager, store, handle.sessionId, VerityMcpServer(manager, store, hierarchyDiffRenderer = renderer).create())
  }

  private suspend fun Server.call(name: String, arguments: JsonObject? = null): CallToolResult = tools.getValue(name).handler.invoke(StubClientConnection(), CallToolRequest(CallToolRequestParams(name, arguments)))

  private fun CallToolResult.json(): JsonObject {
    assertThat(content.size).isEqualTo(1)
    return Json.parseToJsonElement((content.single() as TextContent).text).jsonObject
  }

  private fun CallToolResult.assertError(code: String): JsonObject {
    assertThat(isError).isEqualTo(true)
    val error = json().getValue("error").jsonObject
    assertThat(error.getValue("code").jsonPrimitive.content).isEqualTo(code)
    assertThat(error.getValue("message").jsonPrimitive.content.isNotBlank()).isTrue()
    assertThat(error.getValue("remediation").jsonPrimitive.content.isNotBlank()).isTrue()
    return error
  }

  @Test
  fun `filtered captures retain hidden attributes for subsequent full tree diff`() = runTest {
    val before = HierarchyNode(attributes = mapOf("text" to "Home", "hidden" to "before"), states = setOf("focused"))
    val after = before.copy(attributes = before.attributes + ("hidden" to "after"))
    val trees = listOf(before, after)
    var captures = 0
    val fake = FakeDeviceSession()
    val device = object : DeviceSession by fake {
      override suspend fun captureHierarchyTree(): HierarchyNode = trees.getOrNull(captures++) ?: error("diff must not capture")
    }
    val manager = McpDeviceSessionManager { _, _, _ -> device }
    val store = McpHierarchySnapshotStore()
    val id = manager.open(Platform.ANDROID_TV).sessionId
    val server = VerityMcpServer(manager, store).create()
    val args = buildJsonObject {
      put("session_id", id.toString())
      put("filter", "focus")
    }
    val ids = mutableListOf<String>()
    for (tree in trees) {
      val capture = server.call("capture_hierarchy", args)
      assertThat(capture.isError).isIn(null, false)
      val text = (capture.content.single() as TextContent).text
      assertThat(text.contains("hidden")).isEqualTo(false)
      ids += text.lineSequence().first().removePrefix("snapshot_id: ")
    }
    val result = server.call("diff_hierarchy", buildJsonObject { put("session_id", id.toString()) })
    assertThat(result.isError).isIn(null, false)
    val json = result.json()
    assertThat(json.getValue("before_snapshot_id").jsonPrimitive.content).isEqualTo(ids[0])
    assertThat(json.getValue("after_snapshot_id").jsonPrimitive.content).isEqualTo(ids[1])
    assertThat(json.getValue("changed").jsonObject.getValue("count").jsonPrimitive.content).isEqualTo("1")
    assertThat(json.getValue("changed").jsonObject.getValue("samples").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content.contains("hidden")).isTrue()
    assertThat(captures).isEqualTo(2)
  }

  @Test
  fun `handler returns bounded parsed success with full focused and changed counts`() = runTest {
    val f = fixture()
    fun tree(prefix: String) = HierarchyNode(
      children = List(25) {
        HierarchyNode(attributes = mapOf("text" to (prefix + "😀" + it).repeat(400)), states = setOf("focused"))
      },
    )
    val a = f.store.add(f.sessionId, tree("before"))
    val b = f.store.add(f.sessionId, tree("after"))
    val response = f.server.call("diff_hierarchy", f.arguments())
    assertThat(response.isError).isIn(null, false)
    assertThat((response.content.single() as TextContent).text.length <= 16_000).isTrue()
    val json = response.json()
    assertThat(json.getValue("before_snapshot_id").jsonPrimitive.content).isEqualTo(a.toString())
    assertThat(json.getValue("after_snapshot_id").jsonPrimitive.content).isEqualTo(b.toString())
    assertThat(json.getValue("rendered_truncated").jsonPrimitive.content).isEqualTo("true")
    for (name in listOf("changed", "focus_before", "focus_after")) {
      val category = json.getValue(name).jsonObject
      val samples = category.getValue("samples").jsonArray
      assertThat(category.getValue("count").jsonPrimitive.content).isEqualTo("25")
      assertThat(category.getValue("omitted_count").jsonPrimitive.content.toInt()).isEqualTo(25 - samples.size)
      assertThat(category.getValue("truncated").jsonPrimitive.content).isEqualTo("true")
      assertThat(samples.size <= 20).isTrue()
      for (sample in samples) assertThat(sample.toString().length <= 500).isTrue()
    }
  }

  @Test
  fun `admitted diff renders retained trees before close disposes the device and clears captures`() = runTest {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val f = fixture { id, pair ->
      entered.complete(Unit)
      release.await()
      HierarchyDiff.render(id, pair)
    }
    val a = f.store.add(f.sessionId, HierarchyNode(attributes = mapOf("text" to "before"), states = setOf("focused")))
    val b = f.store.add(f.sessionId, HierarchyNode(attributes = mapOf("text" to "after")))
    val diff = async(start = CoroutineStart.UNDISPATCHED) { f.server.call("diff_hierarchy", f.arguments()) }
    entered.await()
    // Captures can proceed after pair resolution; the retained pair survives their eviction.
    repeat(11) { f.store.add(f.sessionId, HierarchyNode()) }
    assertThat(f.store.get(f.sessionId, a)).isEqualTo(null)
    assertThat(f.store.get(f.sessionId, b)).isEqualTo(null)
    val close = async(start = CoroutineStart.UNDISPATCHED) { f.server.call("close_session", f.arguments()) }
    assertThat(f.manager.isOpen(f.sessionId)).isEqualTo(false)
    assertThat(diff.isCompleted).isEqualTo(false)
    assertThat(close.isCompleted).isEqualTo(false)
    assertThat(f.fake.closed).isEqualTo(false)
    release.complete(Unit)
    val response = diff.await()
    assertThat(response.isError).isIn(null, false)
    val json = response.json()
    assertThat(json.getValue("before_snapshot_id").jsonPrimitive.content).isEqualTo(a.toString())
    assertThat(json.getValue("after_snapshot_id").jsonPrimitive.content).isEqualTo(b.toString())
    assertThat(json.getValue("changed").jsonObject.getValue("count").jsonPrimitive.content).isEqualTo("1")
    assertThat(json.getValue("focus_before").jsonObject.getValue("count").jsonPrimitive.content).isEqualTo("1")
    assertThat(close.await().isError).isIn(null, false)
    assertThat(f.fake.closed).isTrue()
    assertThat(f.store.latest(f.sessionId)).isEqualTo(null)
  }

  @Test
  fun `caller cancellation propagates and releases the session mutex`() = runTest {
    val entered = CompletableDeferred<Unit>()
    val blocked = CompletableDeferred<Unit>()
    val f = fixture { id, pair ->
      entered.complete(Unit)
      blocked.await()
      HierarchyDiff.render(id, pair)
    }
    f.store.add(f.sessionId, HierarchyNode())
    f.store.add(f.sessionId, HierarchyNode())
    val diff = async(start = CoroutineStart.UNDISPATCHED) { f.server.call("diff_hierarchy", f.arguments()) }
    entered.await()
    diff.cancel(CancellationException("caller cancelled"))
    assertFailsWith<CancellationException> { diff.await() }
    assertThat(f.manager.isOpen(f.sessionId)).isTrue()
    assertThat(f.server.call("close_session", f.arguments()).isError).isIn(null, false)
    assertThat(f.fake.closed).isTrue()
  }

  @Test
  fun `callback argument failure preserves existing unexpected error behavior`() = runTest {
    val f = fixture { _, _ -> throw IllegalArgumentException("renderer failed") }
    f.store.add(f.sessionId, HierarchyNode())
    f.store.add(f.sessionId, HierarchyNode())
    val response = f.server.call("diff_hierarchy", f.arguments())
    assertThat(response.isError).isEqualTo(true)
    assertThat((response.content.single() as TextContent).text).isEqualTo("IllegalArgumentException: renderer failed")
    assertThat(f.manager.isOpen(f.sessionId)).isTrue()
  }

  @Test
  fun `diff queued before session removal rejects when it acquires the session mutex`() = runTest {
    val f = fixture()
    f.store.add(f.sessionId, HierarchyNode())
    f.store.add(f.sessionId, HierarchyNode())
    val holderEntered = CompletableDeferred<Unit>()
    val releaseHolder = CompletableDeferred<Unit>()
    val holder = launch(start = CoroutineStart.UNDISPATCHED) {
      f.manager.withSession(f.sessionId) {
        holderEntered.complete(Unit)
        releaseHolder.await()
      }
    }
    holderEntered.await()
    val diff = async(start = CoroutineStart.UNDISPATCHED) { f.server.call("diff_hierarchy", f.arguments()) }
    val close = async(start = CoroutineStart.UNDISPATCHED) { f.server.call("close_session", f.arguments()) }
    assertThat(f.manager.isOpen(f.sessionId)).isEqualTo(false)
    assertThat(diff.isCompleted).isEqualTo(false)
    assertThat(close.isCompleted).isEqualTo(false)
    assertThat(f.fake.closed).isEqualTo(false)
    releaseHolder.complete(Unit)
    holder.join()
    diff.await().assertError("session_unavailable")
    assertThat(close.await().isError).isIn(null, false)
    assertThat(f.fake.closed).isTrue()
    assertThat(f.store.latest(f.sessionId)).isEqualTo(null)
  }

  @Test
  fun `diff rejects absent blank malformed and nonstring IDs as invalid arguments`() = runTest {
    val f = fixture()
    f.store.add(f.sessionId, HierarchyNode())
    f.store.add(f.sessionId, HierarchyNode())
    for (parameter in listOf("session_id", "before_snapshot_id", "after_snapshot_id")) {
      for (value in listOf(JsonPrimitive(""), JsonPrimitive(" "), JsonPrimitive("not-a-uuid"), JsonPrimitive("1-1-1-1-1"), JsonPrimitive(123), JsonPrimitive(true), JsonNull, buildJsonObject {})) {
        val args = buildJsonObject {
          put("session_id", f.sessionId.toString())
          put(parameter, value)
        }
        val error = f.server.call("diff_hierarchy", args).assertError("invalid_argument")
        assertThat(error.getValue("parameter").jsonPrimitive.content).isEqualTo(parameter)
      }
    }
    for (args in listOf(null, buildJsonObject {})) {
      val error = f.server.call("diff_hierarchy", args).assertError("invalid_argument")
      assertThat(error.getValue("parameter").jsonPrimitive.content).isEqualTo("session_id")
    }
  }

  @Test
  fun `diff reports typed snapshot failures without leaking other sessions and honors explicit precedence`() = runTest {
    val f = fixture()
    val a = f.store.add(f.sessionId, HierarchyNode())
    repeat(10) { f.store.add(f.sessionId, HierarchyNode()) }
    val foreignSession = UUID.randomUUID()
    val foreign = f.store.add(foreignSession, HierarchyNode())
    val missing = UUID.randomUUID()
    for (unavailable in listOf(a, foreign, missing)) {
      for (parameter in listOf("before_snapshot_id", "after_snapshot_id")) {
        val args = buildJsonObject {
          put("session_id", f.sessionId.toString())
          put(parameter, unavailable.toString())
        }
        val response = f.server.call("diff_hierarchy", args)
        val error = response.assertError("snapshot_unavailable")
        assertThat(error.getValue("parameter").jsonPrimitive.content).isEqualTo(parameter)
        assertThat(error.getValue("snapshot_id").jsonPrimitive.content).isEqualTo(unavailable.toString())
        assertThat(error.getValue("session_id").jsonPrimitive.content).isEqualTo(f.sessionId.toString())
        assertThat((response.content.single() as TextContent).text.contains(foreignSession.toString())).isEqualTo(false)
      }
    }
    f.store.clear(f.sessionId)
    val explicitAfter = f.server.call("diff_hierarchy", f.arguments(after = missing)).assertError("snapshot_unavailable")
    assertThat(explicitAfter.getValue("parameter").jsonPrimitive.content).isEqualTo("after_snapshot_id")
    val both = f.server.call("diff_hierarchy", f.arguments(missing, foreign)).assertError("snapshot_unavailable")
    assertThat(both.getValue("parameter").jsonPrimitive.content).isEqualTo("before_snapshot_id")
  }

  @Test
  fun `diff explains default shortages and permits one-capture explicit self comparison`() = runTest {
    val f = fixture()
    for (available in 0..1) {
      val error = f.server.call("diff_hierarchy", f.arguments()).assertError("insufficient_captures")
      assertThat(error.getValue("parameter").jsonPrimitive.content).isEqualTo("before_snapshot_id")
      assertThat(error.getValue("required_captures").jsonPrimitive.content).isEqualTo("2")
      assertThat(error.getValue("available_captures").jsonPrimitive.content).isEqualTo(available.toString())
      if (available == 0) f.store.add(f.sessionId, HierarchyNode())
    }
    f.store.clear(f.sessionId)
    val single = f.store.add(f.sessionId, HierarchyNode())
    val result = f.server.call("diff_hierarchy", f.arguments(before = single))
    assertThat(result.isError).isIn(null, false)
    assertThat(result.json().getValue("after_snapshot_id").jsonPrimitive.content).isEqualTo(single.toString())
  }

  @Test
  fun `session removed before diff lookup produces a structured error`() = runTest {
    val f = fixture()
    f.store.add(f.sessionId, HierarchyNode())
    f.server.call("close_session", f.arguments())
    assertThat(f.fake.closed).isTrue()
    assertThat(f.store.latest(f.sessionId)).isEqualTo(null)
    val closed = f.server.call("diff_hierarchy", f.arguments()).assertError("session_unavailable")
    assertThat(closed.getValue("session_id").jsonPrimitive.content).isEqualTo(f.sessionId.toString())
    val missing = UUID.randomUUID()
    val unknown = f.server.call("diff_hierarchy", buildJsonObject { put("session_id", missing.toString()) }).assertError("session_unavailable")
    assertThat(unknown.getValue("session_id").jsonPrimitive.content).isEqualTo(missing.toString())
  }

  @Test
  fun `diff schema requires session and exposes independent optional snapshot IDs`() {
    val server = VerityMcpServer().create()
    val schema = server.tools.getValue("diff_hierarchy").tool.inputSchema
    assertThat(schema.required).isEqualTo(listOf("session_id"))
    assertThat(schema.properties!!.keys).isEqualTo(setOf("session_id", "before_snapshot_id", "after_snapshot_id"))
    for (value in schema.properties!!.values) assertThat(value.jsonObject.getValue("type").jsonPrimitive.content).isEqualTo("string")
  }

  @Test
  fun `diff resolves all omission combinations and self pairs without capturing`() = runTest {
    val f = fixture()
    val before = HierarchyNode(attributes = mapOf("text" to "before"), states = setOf("focused"))
    val after = HierarchyNode(attributes = mapOf("text" to "after"), children = listOf(HierarchyNode()))
    val a = f.store.add(f.sessionId, before)
    val b = f.store.add(f.sessionId, after)
    for ((beforeId, afterId) in listOf(a to b, null to b, a to null, null to null, a to a)) {
      val response = f.server.call("diff_hierarchy", f.arguments(beforeId, afterId))
      assertThat(response.isError).isIn(null, false)
      val json = response.json()
      assertThat(json.getValue("session_id").jsonPrimitive.content).isEqualTo(f.sessionId.toString())
      assertThat(json.getValue("before_snapshot_id").jsonPrimitive.content).isEqualTo(a.toString())
      assertThat(json.getValue("after_snapshot_id").jsonPrimitive.content).isEqualTo((afterId ?: b).toString())
      assertThat(json.getValue("changed").jsonObject.getValue("count").jsonPrimitive.content).isEqualTo(if (afterId == a) "0" else "1")
      assertThat(json.getValue("added").jsonObject.getValue("count").jsonPrimitive.content).isEqualTo(if (afterId == a) "0" else "1")
      assertThat(json.getValue("focus_before").jsonObject.getValue("count").jsonPrimitive.content).isEqualTo("1")
      assertThat(json.getValue("focus_after").jsonObject.getValue("count").jsonPrimitive.content).isEqualTo(if (afterId == a) "1" else "0")
      assertThat(json.getValue("rendered_truncated").jsonPrimitive.content).isEqualTo("false")
    }
  }
}
