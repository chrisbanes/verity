package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThan
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.chrisbanes.verity.agent.ModelFailureException
import me.chrisbanes.verity.agent.ModelFailureKind
import me.chrisbanes.verity.agent.ModelRequestStage
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.core.model.Platform

class CodexModelBackendTest {
  private class CallerCancellation(val marker: Any) : CancellationException("caller-owned")

  private fun request(name: String = "navigator", effort: String? = null, images: List<LabeledLocalImage> = emptyList()) = ModelRequest(
    promptName = name,
    systemText = "system-$name",
    userText = "user-$name",
    images = images,
    model = SelectedRoleModel.Codex("exact-selected-model"),
    settings = ModelRequestSettings.Codex(effort),
  )

  private suspend fun fixture(scenario: String = "model-success", test: suspend (FakeCodexLauncher, CodexModelBackend) -> Unit) {
    val fake = FakeCodexLauncher(scenario)
    var backend: CodexModelBackend? = null
    try {
      backend = fake.prepare()
      withTimeout(12_000) { test(fake, backend) }
    } finally {
      withContext(NonCancellable) {
        try {
          try {
            backend?.close()
          } catch (_: CodexFailure) { /* Failure outcome is checked by the body. */ }
          fake.verifyCleanup()
        } finally {
          fake.cleanupReceipts()
        }
      }
    }
  }

  private fun assertSafeCleanup(primary: Throwable) {
    assertThat(primary.cause).isEqualTo(null)
    assertThat(primary.suppressed.isNotEmpty()).isTrue()
    primary.suppressed.forEach { secondary ->
      assertThat((secondary as CodexFailure).kind).isEqualTo(CodexFailureKind.CLEANUP)
      assertThat(secondary.cause).isEqualTo(null)
      assertThat(secondary.message).isEqualTo("Codex cleanup failure")
    }
  }

  @Test
  fun `callback protocol rejection retains backend primary and navigator invalid response despite cleanup failure`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-callback-unknown") { fake, backend ->
        var primary: CodexFailure? = null
        val navigator = NavigatorAgent("") { _, _ ->
          try {
            backend.execute(request())
          } catch (failure: CodexFailure) {
            primary = failure
            throw failure
          }
        }
        supervisorScope {
          val result = async { runCatching { navigator.generate(listOf("launch"), "test.app", Platform.ANDROID_TV) } }
          withTimeout(3_000) { while (!backend.client.ownsTurn("thread-1", "turn-1")) delay(5) }
          fake.releaseCallbacks()
          val failure = result.await().exceptionOrNull() as ModelFailureException
          assertThat(failure.stage).isEqualTo(ModelRequestStage.NAVIGATOR_FLOW)
          assertThat(failure.failure).isEqualTo(ModelFailureKind.INVALID_RESPONSE)
          assertThat(failure.cause).isEqualTo(null)
          val backendFailure = checkNotNull(primary)
          assertThat(backendFailure.kind).isEqualTo(CodexFailureKind.PROTOCOL)
          assertSafeCleanup(backendFailure)
        }
      }
    }
  }

  @Test
  fun `preparation protocol rejection and owned timeout retain safe primary with cleanup diagnostic`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("callback" to CodexFailureKind.PROTOCOL, "version-timeout" to CodexFailureKind.STARTUP_TIMEOUT).forEach { (scenario, kind) ->
        val fake = FakeCodexLauncher(scenario).also { it.failCleanup = true }
        try {
          val failure = assertFailsWith<CodexFailure> { fake.prepare(startupMillis = 1_000) }
          assertThat(failure.kind).isEqualTo(kind)
          assertSafeCleanup(failure)
          fake.verifyCleanup()
        } finally {
          fake.cleanupReceipts()
        }
      }
    }
  }

  @Test
  fun `unknown preparation error normalizes before secondary cleanup without retaining raw cause`() = runTest {
    withContext(Dispatchers.Default) {
      val fake = FakeCodexLauncher()
      var launches = 0
      try {
        val failure = assertFailsWith<CodexFailure> {
          CodexModelBackend.prepare(
            executable = { Path.of("injected-jvm-fake") },
            host = "Mac OS X",
            environment = emptyMap(),
            launch = { command, directory, environment ->
              if (++launches == 4) {
                fake.failCleanup = true
                throw java.io.IOException("secret-primary-cause")
              }
              fake.launch(command, directory, environment)
            },
          )
        }
        assertThat(failure.kind).isEqualTo(CodexFailureKind.PROTOCOL)
        assertSafeCleanup(failure)
        fake.verifyCleanup()
      } finally {
        fake.cleanupReceipts()
      }
    }
  }

  @Test
  fun `caller preparation and active request cancellation retain identity and fixed secondary cleanup`() = runTest {
    withContext(Dispatchers.Default) {
      supervisorScope {
        val fake = FakeCodexLauncher("version-timeout").also { it.failCleanup = true }
        val cancellation = CallerCancellation(Any())
        var observed: CancellationException? = null
        val caller = launch {
          try {
            fake.prepare()
          } catch (failure: CancellationException) {
            observed = failure
            throw failure
          }
        }
        withTimeout(3_000) { while (fake.children.isEmpty()) delay(5) }
        caller.cancel(cancellation)
        caller.join()
        assertThat(observed).isSameInstanceAs(cancellation)
        assertSafeCleanup(cancellation)
        fake.verifyCleanup()
        fake.cleanupReceipts()
      }
      fixture("model-hang") { fake, backend ->
        fake.failCleanup = true
        supervisorScope {
          val cancellation = CallerCancellation(Any())
          var observed: CancellationException? = null
          val caller = launch {
            try {
              backend.execute(request())
            } catch (failure: CancellationException) {
              observed = failure
              throw failure
            }
          }
          awaitMethod(fake, "turn/start")
          caller.cancel(cancellation)
          caller.join()
          assertThat(observed).isSameInstanceAs(cancellation)
          assertSafeCleanup(cancellation)
        }
      }
    }
  }

  @Test
  fun `successful request with failed unsubscribe still reports cleanup as primary`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-unsubscribe-failed") { _, backend ->
        val failure = assertFailsWith<CodexFailure> { backend.execute(request()) }
        assertThat(failure.kind).isEqualTo(CodexFailureKind.CLEANUP)
        assertThat(failure.cause).isEqualTo(null)
      }
    }
  }

  private suspend fun awaitMethod(fake: FakeCodexLauncher, method: String) {
    withTimeout(3_000) { while (fake.capturedFrames().none { it["method"] == JsonPrimitive(method) }) delay(10) }
  }

  @Test
  fun `navigator scroll tree and visual preserve exact prompt settings image order and distinct ephemeral IDs`() = runTest {
    withContext(Dispatchers.Default) {
      fixture { fake, backend ->
        val names = listOf("navigator-flow", "navigator-scroll", "inspector-tree", "inspector-visual")
        val ids = mutableSetOf<String>()
        names.forEachIndexed { index, name ->
          val images = if (index == 3) listOf(LabeledLocalImage("Current screenshot:", Path.of("/synthetic/current.png")), LabeledLocalImage("Reference first:", Path.of("/synthetic/first.png")), LabeledLocalImage("Reference different:", Path.of("/synthetic/different.png"))) else emptyList()
          val input = request(name, if (index % 2 == 0) null else "low", images)
          val reply = backend.execute(input)
          assertThat(reply.finishReason).isEqualTo("stop")
          assertThat(reply.metaInfo.modelId).isEqualTo("exact-selected-model")
          val captured = Json.parseToJsonElement(reply.textContent()).jsonObject
          val thread = captured.getValue("thread").jsonObject
          assertThat(thread["model"]).isEqualTo(JsonPrimitive("exact-selected-model"))
          assertThat(thread["modelProvider"]).isEqualTo(JsonPrimitive("openai"))
          assertThat(thread["baseInstructions"]).isEqualTo(JsonPrimitive(input.systemText))
          assertThat(thread["developerInstructions"]).isEqualTo(JsonPrimitive(""))
          listOf("runtimeWorkspaceRoots", "selectedCapabilityRoots", "dynamicTools", "environments").forEach { assertThat(thread[it]).isEqualTo(JsonArray(emptyList())) }
          listOf("experimentalRawEvents", "ephemeral").forEach { assertThat(thread[it]).isEqualTo(JsonPrimitive(true)) }
          assertThat(thread["allowProviderModelFallback"]).isEqualTo(JsonPrimitive(false))
          assertThat(thread["approvalPolicy"]).isEqualTo(JsonPrimitive("never"))
          assertThat(thread["sandbox"]).isEqualTo(JsonPrimitive("read-only"))
          assertThat(thread["config"]).isEqualTo(backend.isolation.threadConfig())
          val turn = captured.getValue("turn").jsonObject
          assertThat(turn["serviceTierForTurn"]).isEqualTo(JsonPrimitive("default"))
          if (index % 2 == 0) assertThat(turn.containsKey("effort")).isFalse() else assertThat(turn["effort"]).isEqualTo(JsonPrimitive("low"))
          val expected = buildList {
            add(
              buildJsonObject {
                put("type", "text")
                put("text", input.userText)
              },
            )
            images.forEach { image ->
              add(
                buildJsonObject {
                  put("type", "text")
                  put("text", image.label)
                },
              )
              add(
                buildJsonObject {
                  put("type", "localImage")
                  put("path", image.path.toString())
                },
              )
            }
          }
          assertThat(turn["input"]).isEqualTo(JsonArray(expected))
          assertThat(ids.add(captured.getValue("threadId").toString())).isTrue()
          assertThat(reply.id).isEqualTo(captured.getValue("turnId").let { (it as JsonPrimitive).content })
        }
        val frames = fake.capturedFrames()
        assertThat(frames.count { it["method"] == JsonPrimitive("thread/unsubscribe") }).isEqualTo(4)
        assertThat(frames.filter { it["method"] != null }.map { it.getValue("id") }.distinct().size).isEqualTo(12)
        assertThat(fake.children.size).isEqualTo(4)
      }
    }
  }

  @Test
  fun `concurrent callers serialize whole ephemeral requests`() = runTest {
    withContext(Dispatchers.Default) {
      fixture { fake, backend ->
        supervisorScope {
          val replies = (1..3).map { async { backend.execute(request("request-$it")) } }.map { it.await() }
          assertThat(replies.map { it.id }.distinct().size).isEqualTo(3)
        }
        assertThat(fake.capturedFrames().mapNotNull { (it["method"] as? JsonPrimitive)?.content }).isEqualTo(List(3) { listOf("thread/start", "turn/start", "thread/unsubscribe") }.flatten())
      }
    }
  }

  @Test
  fun `all ordinary executable items started or completed irreversibly reject later valid final and success`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall", "collabAgentToolCall", "subAgentActivity", "webSearch", "imageView", "imageGeneration", "sleep", "plan", "functionCallOutput", "hookPrompt", "unknownExecutable").forEach { type ->
        listOf("started", "completed").forEach { phase ->
          fixture("model-normal-$type-$phase") { fake, backend ->
            val failure = assertFailsWith<CodexFailure> { backend.execute(request()) }
            assertThat(failure.cause).isEqualTo(null)
            assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("turn/interrupt") }).isEqualTo(1)
            assertThat(assertFailsWith<CodexFailure> { backend.execute(request()) }.kind).isEqualTo(CodexFailureKind.REQUEST)
          }
        }
      }
    }
  }

  @Test
  fun `all raw calls outputs output-only and unknown executable variants permanently reject`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("custom_tool_call", "custom_tool_call_output", "function_call", "function_call_output", "local_shell_call", "web_search_call", "tool_search_call", "tool_search_output", "image_generation_call", "configuration_update", "agent_message", "other", "unknown_call").forEach { type ->
        fixture("model-raw-$type") { fake, backend ->
          assertFailsWith<CodexFailure> { backend.execute(request()) }
          assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("turn/interrupt") }).isEqualTo(1)
        }
      }
    }
  }

  @Test
  fun `plan tool disabled host rerouting notifications and every callback are rejected`() = runTest {
    withContext(Dispatchers.Default) {
      (listOf("model-callback-auth", "model-callback-tool", "model-callback-unknown") + listOf("turn/plan/updated", "item/plan/delta", "item/commandExecution/outputDelta", "item/mcpToolCall/progress", "item/disabledHost/attempt", "model/rerouted").map { "model-notification-$it" }).forEach { scenario ->
        fixture(scenario) { fake, backend ->
          if (scenario.startsWith("model-callback-")) {
            supervisorScope {
              val result = async { runCatching { backend.execute(request()) } }
              withTimeout(3_000) { while (!backend.client.ownsTurn("thread-1", "turn-1")) delay(5) }
              fake.releaseCallbacks()
              val failure = result.await().exceptionOrNull() as CodexFailure
              assertThat(failure.kind).isEqualTo(CodexFailureKind.PROTOCOL)
              assertSafeCleanup(failure)
            }
          } else {
            assertFailsWith<CodexFailure> { backend.execute(request()) }
          }
          assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("turn/interrupt") }).isEqualTo(1)
          if (scenario.startsWith("model-callback-")) {
            val refusal = fake.capturedFrames().single { it.containsKey("error") }
            assertThat(refusal["id"]).isEqualTo(JsonPrimitive("server-request"))
            assertThat(refusal["error"]?.jsonObject?.get("message")).isEqualTo(JsonPrimitive("Requests are disabled"))
          }
        }
      }
    }
  }

  @Test
  fun `preack callbacks and raw attempts close exact owned process without an unowned interrupt`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("model-preack-thread-unknown/request", "model-preack-turn-account/chatgptAuthTokens/refresh", "model-preack-turn-unknown/request", "model-preack-turn-raw").forEach { scenario ->
        fixture(scenario) { fake, backend ->
          assertFailsWith<CodexFailure> { backend.execute(request()) }
          assertThat(fake.children.all { !it.process.isAlive }).isTrue()
          assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("turn/interrupt") }).isEqualTo(0)
          if (!scenario.endsWith("raw")) assertThat(fake.capturedFrames().count { it.containsKey("error") }).isEqualTo(1)
        }
      }
    }
  }

  @Test
  fun `wrong stale RPC thread turn and unknown echo semantics fail closed`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("model-wrong-rpc", "model-wrong-thread", "model-wrong-turn", "model-stale-turn", "model-stale-delta", "model-echo-wrong-model", "model-echo-null-environments").forEach { scenario ->
        fixture(scenario) { _, backend -> assertFailsWith<CodexFailure> { backend.execute(request()) } }
      }
    }
  }

  @Test
  fun `previous successful thread and turn cannot supply the next request reply`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-stale-after-success") { fake, backend ->
        assertThat(backend.execute(request("first")).finishReason).isEqualTo("stop")
        assertFailsWith<CodexFailure> { backend.execute(request("second")) }
        val interrupts = fake.capturedFrames().filter { it["method"] == JsonPrimitive("turn/interrupt") }
        assertThat(interrupts.size).isEqualTo(1)
        assertThat(interrupts.single()["params"]).isEqualTo(
          buildJsonObject {
            put("threadId", "thread-2")
            put("turnId", "turn-2")
          },
        )
      }
    }
  }

  @Test
  fun `relative local image retains the callers absolute path under an empty child cwd`() = runTest {
    withContext(Dispatchers.Default) {
      fixture { _, backend ->
        val image = LabeledLocalImage("Relative screenshot:", Path.of("synthetic/relative.png"))
        val reply = backend.execute(request("relative", images = listOf(image)))
        val captured = Json.parseToJsonElement(reply.textContent()).jsonObject
        val input = captured.getValue("turn").jsonObject.getValue("input").jsonArray
        assertThat(input[1].jsonObject["text"]).isEqualTo(JsonPrimitive(image.label))
        assertThat(input[2].jsonObject["path"]).isEqualTo(JsonPrimitive(image.path.toAbsolutePath().toString()))
      }
    }
  }

  @Test
  fun `thread ACK mismatch and unsolicited turn never claim unverified ownership`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("model-mismatched-thread-ack", "model-unsolicited-turn").forEach { scenario ->
        fixture(scenario) { fake, backend ->
          assertFailsWith<CodexFailure> { backend.execute(request()) }
          val frames = fake.capturedFrames()
          assertThat(frames.count { it["method"] == JsonPrimitive("turn/start") }).isEqualTo(0)
          assertThat(frames.count { it["method"] == JsonPrimitive("turn/interrupt") }).isEqualTo(0)
          val unsubscribes = frames.filter { it["method"] == JsonPrimitive("thread/unsubscribe") }
          assertThat(unsubscribes.size).isEqualTo(1)
          assertThat(unsubscribes.single()["params"]).isEqualTo(buildJsonObject { put("threadId", "thread-1") })
        }
      }
    }
  }

  @Test
  fun `completed ephemeral thread ID cannot be acquired again`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-reused-thread-after-success") { fake, backend ->
        assertThat(backend.execute(request("first")).finishReason).isEqualTo("stop")
        assertFailsWith<CodexFailure> { backend.execute(request("second")) }
        val frames = fake.capturedFrames()
        assertThat(frames.count { it["method"] == JsonPrimitive("thread/start") }).isEqualTo(2)
        assertThat(frames.count { it["method"] == JsonPrimitive("turn/start") }).isEqualTo(1)
        assertThat(frames.count { it["method"] == JsonPrimitive("thread/unsubscribe") }).isEqualTo(1)
        assertThat(frames.count { it["method"] == JsonPrimitive("turn/interrupt") }).isEqualTo(0)
      }
    }
  }

  @Test
  fun `attempts and contradictory terminals during successful unsubscribe reject the result`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("model-unsubscribe-command", "model-unsubscribe-raw", "model-unsubscribe-turn-started", "model-unsubscribe-turn-failed").forEach { scenario ->
        fixture(scenario) { fake, backend ->
          assertFailsWith<CodexFailure> { backend.execute(request()) }
          assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("thread/unsubscribe") }).isEqualTo(1)
          assertThat(fake.children.all { !it.process.isAlive }).isTrue()
        }
      }
    }
  }

  @Test
  fun `missing prompt phase input and terminal schema surfaces fail before any model request`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("model-schema-missing-developer", "model-schema-wrong-input", "model-schema-missing-phase", "model-schema-missing-terminal").forEach { scenario ->
        val fake = FakeCodexLauncher(scenario)
        try {
          assertThat(assertFailsWith<CodexFailure> { fake.prepare() }.kind).isEqualTo(CodexFailureKind.PROTOCOL)
          assertThat(fake.children.size).isEqualTo(2)
          assertThat(fake.capturedFrames().isEmpty()).isTrue()
        } finally {
          withContext(NonCancellable) {
            try {
              fake.verifyCleanup()
            } finally {
              fake.cleanupReceipts()
            }
          }
        }
      }
    }
  }

  @Test
  fun `only latest completed final answer wins with phase unknown legacy fallback`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-final-priority") { _, backend -> assertThat(backend.execute(request()).textContent()).isEqualTo("selected-final") }
      fixture("model-latest-final") { _, backend -> assertThat(backend.execute(request()).textContent()).isEqualTo("latest-final") }
      fixture("model-legacy") { _, backend -> assertThat(Json.parseToJsonElement(backend.execute(request()).textContent()).jsonObject["threadId"] != null).isTrue() }
      listOf("model-failed", "model-interrupted", "model-blank", "model-absent", "model-commentary-only", "model-unknown-phase").forEach { scenario -> fixture(scenario) { _, backend -> assertFailsWith<CodexFailure> { backend.execute(request()) } } }
    }
  }

  @Test
  fun `malformed truncated overflow lost transport and unsubscribe failure cannot return success`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("model-malformed-json", "model-invalid-utf8", "model-truncated", "model-frame-overflow", "model-text-overflow", "model-loss", "model-unsubscribe-failed", "model-unsubscribe-hang").forEach { scenario ->
        fixture(scenario) { _, backend -> assertFailsWith<CodexFailure> { backend.execute(request()) } }
      }
    }
  }

  @Test
  fun `more than sixty four sequential relevant notifications succeeds without overflowing queue`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-many-events") { _, backend -> assertThat(backend.execute(request()).finishReason).isEqualTo("stop") }
    }
  }

  @Test
  fun `ack immediately followed by delta is correlated after consumer validates ownership`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-ack-immediate-delta") { _, backend -> assertThat(backend.execute(request()).finishReason).isEqualTo("stop") }
    }
  }

  @Test
  fun `successful owner close stays idempotent after original cleanup deadline expires`() = runTest {
    withContext(Dispatchers.Default) {
      fixture { fake, backend ->
        var now = 0L
        val deadline = backend.resources.beginCleanup(CodexCleanupDeadline { now })
        backend.close()
        now = 6_000_000_000L
        assertThat(deadline.remainingMillis()).isEqualTo(0L)
        backend.close()
        assertThat(fake.children.all { !it.process.isAlive }).isTrue()
      }
    }
  }

  @Test
  fun `expired owner close after failed or caller cancelled request completes empty owned supervisor`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("model-blank", "model-hang").forEach { scenario ->
        fixture(scenario) { fake, backend ->
          var now = 0L
          val deadline = backend.resources.beginCleanup(CodexCleanupDeadline { now })
          if (scenario == "model-blank") {
            assertThat(assertFailsWith<CodexFailure> { backend.execute(request()) }.kind).isEqualTo(CodexFailureKind.REQUEST)
          } else {
            supervisorScope {
              val cancellation = CallerCancellation(Any())
              var observed: CancellationException? = null
              val caller = launch {
                try {
                  backend.execute(request())
                } catch (e: CancellationException) {
                  observed = e
                  throw e
                }
              }
              awaitMethod(fake, "turn/start")
              caller.cancel(cancellation)
              caller.join()
              assertThat(observed).isSameInstanceAs(cancellation)
            }
          }
          assertThat(fake.children.all { !it.process.isAlive }).isTrue()
          now = 6_000_000_000L
          assertThat(deadline.remainingMillis()).isEqualTo(0L)
          val frames = fake.capturedFrames().size
          backend.close()
          backend.close()
          assertThat(fake.capturedFrames().size).isEqualTo(frames)
        }
      }
    }
  }

  @Test
  fun `reader failure rejects already queued valid final and terminal frames irreversibly`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("model-direct-malformed", "model-direct-overflow").forEach { scenario ->
        val fake = FakeCodexLauncher(scenario)
        val resources = CodexOwnedResources()
        try {
          val directory = resources.directory()
          val client = resources.client(resources.launch(listOf("injected-jvm-fake", "app-server"), directory, emptyMap(), fake::launch))
          withTimeout(3_000) {
            while (true) {
              try {
                client.requireHealthy()
              } catch (_: CodexFailure) {
                break
              }
              delay(10)
            }
          }
          assertFailsWith<CodexFailure> { client.receive() }
        } finally {
          withContext(NonCancellable) {
            try {
              resources.close()
              fake.verifyCleanup()
            } finally {
              fake.cleanupReceipts()
            }
          }
        }
      }
    }
  }

  @Test
  fun `queued caller cancellation does not interrupt active caller and active caller CE is unchanged`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-hang") { fake, backend ->
        supervisorScope {
          val cancellation = CallerCancellation(Any())
          var observed: CancellationException? = null
          val active = launch {
            try {
              backend.execute(request())
            } catch (e: CancellationException) {
              observed = e
              throw e
            }
          }
          awaitMethod(fake, "turn/start")
          val queued = launch { backend.execute(request("queued")) }
          delay(50)
          queued.cancel(CallerCancellation(Any()))
          queued.join()
          assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("turn/interrupt") }).isEqualTo(0)
          assertThat(active.isActive).isTrue()
          assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("thread/start") }).isEqualTo(1)
          active.cancel(cancellation)
          active.join()
          assertThat(observed).isSameInstanceAs(cancellation)
          assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("turn/interrupt") }).isEqualTo(1)
          assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("thread/unsubscribe") }).isEqualTo(1)
        }
      }
    }
  }

  @Test
  fun `owner close cancels only owned work and joins it without cancelling caller siblings`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-hang") { fake, backend ->
        supervisorScope {
          val unrelated = launch { delay(60_000) }
          var fixedFailure: CodexFailure? = null
          val active = launch {
            try {
              backend.execute(request())
            } catch (e: CodexFailure) {
              fixedFailure = e
            }
          }
          awaitMethod(fake, "turn/start")
          backend.close()
          active.join()
          assertThat(fixedFailure?.kind).isEqualTo(CodexFailureKind.REQUEST)
          assertThat(active.isCompleted).isTrue()
          assertThat(unrelated.isActive).isTrue()
          assertThat(assertFailsWith<CodexFailure> { backend.execute(request()) }.kind).isEqualTo(CodexFailureKind.REQUEST)
          assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("turn/interrupt") }).isEqualTo(1)
          unrelated.cancel()
          unrelated.join()
        }
      }
    }
  }

  @Test
  fun `thread status subtype permits only known nonexecuting information and safe cleanup transition`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("model-status-idle", "model-status-active-empty", "model-status-unsubscribe-notLoaded").forEach { scenario ->
        fixture(scenario) { _, backend -> assertThat(backend.execute(request()).finishReason).isEqualTo("stop") }
      }
      listOf("model-status-active-approval", "model-status-active-user", "model-status-active-unknown", "model-status-active-null", "model-status-active-missing", "model-status-unknown", "model-status-systemError", "model-status-notLoaded", "model-status-unsubscribe-approval").forEach { scenario ->
        fixture(scenario) { _, backend -> assertFailsWith<CodexFailure> { backend.execute(request()) } }
      }
    }
  }

  @Test
  fun `reader protocol failure between requests prevents any fresh thread or turn`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-idle-callback") { fake, backend ->
        assertThat(backend.execute(request()).finishReason).isEqualTo("stop")
        fake.releaseCallbacks()
        withTimeout(3_000) {
          while (true) {
            try {
              backend.client.requireHealthy()
            } catch (_: CodexFailure) {
              break
            }
            delay(5)
          }
        }
        assertFailsWith<CodexFailure> { backend.execute(request("next")) }
        assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("thread/start") }).isEqualTo(1)
        assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("turn/start") }).isEqualTo(1)
      }
    }
  }

  @Test
  fun `blocked reader refusal write is unblocked and joined under the same cleanup deadline`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-callback-blocked") { fake, backend ->
        supervisorScope {
          val cancellation = CallerCancellation(Any())
          var observed: CancellationException? = null
          val active = launch {
            try {
              backend.execute(request())
            } catch (e: CancellationException) {
              observed = e
              throw e
            }
          }
          withTimeout(3_000) { while (!backend.client.ownsTurn("thread-1", "turn-1")) delay(5) }
          fake.releaseCallbacks()
          val entry = withTimeout(3_000) { fake.largeWriteEntered.await() }
          assertThat(entry.pid).isEqualTo(fake.children.last().process.pid())
          assertThat(entry.bytes > 3 * 1024 * 1024).isTrue()
          assertThat(fake.largeWriteFinished.isCompleted).isFalse()
          val started = System.nanoTime()
          active.cancel(cancellation)
          active.join()
          assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000L)
          assertThat(observed).isSameInstanceAs(cancellation)
          assertThat(fake.largeWriteFinished.isCompleted).isTrue()
          assertThat(fake.children.all { !it.process.isAlive }).isTrue()
        }
      }
    }
  }

  @Test
  fun `recognized nonexecuting raw message reasoning and compaction are tolerated`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("message", "reasoning", "compaction", "compaction_trigger", "context_compaction").forEach { type ->
        fixture("model-raw-$type") { _, backend -> assertThat(backend.execute(request()).finishReason).isEqualTo("stop") }
      }
    }
  }

  @Test
  fun `blocked active send and cleanup share one five second deadline and terminate exact child`() = runTest {
    withContext(Dispatchers.Default) {
      fixture("model-no-stdin") { fake, backend ->
        supervisorScope {
          var observed: CancellationException? = null
          val cancellation = CallerCancellation(Any())
          val active = launch {
            try {
              backend.execute(request("blocked").copy(userText = "x".repeat(4 * 1024 * 1024)))
            } catch (e: CancellationException) {
              observed = e
              throw e
            }
          }
          // The large turn/start write fills the pipe while the fake stops reading
          // immediately after thread/start, before acknowledging the turn.
          awaitMethod(fake, "thread/start")
          delay(200)
          assertThat(active.isCompleted).isFalse()
          val started = System.nanoTime()
          active.cancel(cancellation)
          active.join()
          assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000L)
          assertThat(observed).isSameInstanceAs(cancellation)
          assertThat(fake.children.all { !it.process.isAlive }).isTrue()
        }
      }
    }
  }
}
