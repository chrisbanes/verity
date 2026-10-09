package me.chrisbanes.verity.cli

import assertk.assertions.isEqualTo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A real JVM child with no native Codex, account, provider, model, or device access. */
internal object FakeCodexAppServer {
  @JvmStatic
  fun main(args: Array<String>) {
    val scenario = args[0]
    if (scenario in setOf("model-direct-malformed", "model-direct-overflow")) {
      println(
        buildJsonObject {
          put("method", "item/completed")
          put(
            "params",
            buildJsonObject {
              put("threadId", "owned-thread")
              put("turnId", "owned-turn")
              put(
                "item",
                buildJsonObject {
                  put("type", "agentMessage")
                  put("text", "valid-looking-final")
                  put("phase", "final_answer")
                },
              )
            },
          )
        },
      )
      event(
        "turn/completed",
        buildJsonObject {
          put("threadId", "owned-thread")
          put("turn", turn("owned-turn", "completed"))
        },
      )
      if (scenario == "model-direct-malformed") println("{malformed") else println("x".repeat(CodexAppServerClient.MAX_FRAME_BYTES + 1))
      return
    }
    if (scenario == "stubborn") Runtime.getRuntime().addShutdownHook(Thread { Thread.sleep(60_000) })
    val command = args.drop(1)
    if (command == listOf("--version")) {
      if (scenario == "version-timeout") Thread.sleep(60_000)
      println(
        when (scenario) {
          "old" -> "codex-cli 0.158.9"
          "malformed-version" -> "secret-invalid"
          "newer" -> "codex-cli 0.200.0"
          else -> "codex-cli 0.159.0"
        },
      )
      return
    }
    if ("generate-json-schema" in command) {
      if (scenario == "schema-timeout") Thread.sleep(60_000)
      val output = Path.of(command[command.indexOf("--out") + 1])
      schemaFixtures.forEach { (name, source) ->
        val path = output.resolve(name)
        Files.createDirectories(path.parent)
        var schema = Json.parseToJsonElement(source).jsonObject
        if (name.endsWith("ThreadStartParams.json") && scenario in setOf("missing-raw", "missing-roots", "wrong-root-type")) {
          val properties = schema.getValue("properties").jsonObject.toMutableMap()
          when (scenario) {
            "missing-raw" -> properties.remove("experimentalRawEvents")
            "missing-roots" -> properties.remove("selectedCapabilityRoots")
            "wrong-root-type" -> properties["selectedCapabilityRoots"] = buildJsonObject { put("type", "object") }
          }
          schema = JsonObject(schema + ("properties" to JsonObject(properties)))
        }
        if (name.endsWith("ThreadStartParams.json") && scenario == "model-schema-missing-developer") schema = JsonObject(schema + ("properties" to JsonObject(schema.getValue("properties").jsonObject - "developerInstructions")))
        if (name.endsWith("TurnStartParams.json") && scenario == "model-schema-wrong-input") schema = JsonObject(schema + ("properties" to JsonObject(schema.getValue("properties").jsonObject + ("input" to buildJsonObject { put("type", "string") }))))
        if (name.endsWith("ItemCompletedNotification.json") && scenario == "model-schema-missing-phase") schema = JsonObject(schema + ("definitions" to JsonObject(schema.getValue("definitions").jsonObject - "MessagePhase")))
        if (name.endsWith("TurnCompletedNotification.json") && scenario == "model-schema-missing-terminal") {
          val definitions = schema.getValue("definitions").jsonObject
          val status = definitions.getValue("TurnStatus").jsonObject
          schema = JsonObject(schema + ("definitions" to JsonObject(definitions + ("TurnStatus" to JsonObject(status + ("enum" to JsonArray(listOf(JsonPrimitive("inProgress")))))))))
        }
        if (name.endsWith("InitializeParams.json") && scenario in setOf("missing-gateway", "missing-opt-out")) {
          val definitions = schema.getValue("definitions").jsonObject
          val capabilities = definitions.getValue("InitializeCapabilities").jsonObject
          val properties = capabilities.getValue("properties").jsonObject - (if (scenario == "missing-gateway") "explicitGatewayOauth" else "optOutNotificationMethods")
          schema = JsonObject(schema + ("definitions" to JsonObject(definitions + ("InitializeCapabilities" to JsonObject(capabilities + ("properties" to JsonObject(properties)))))))
        }
        if (name.endsWith("RawResponseItemCompletedNotification.json") && scenario == "missing-raw-definition") schema = JsonObject(schema - "definitions")
        if (name.endsWith("ThreadStartResponse.json") && scenario == "wrong-echo-type") schema = JsonObject(schema + ("properties" to JsonObject(schema.getValue("properties").jsonObject + ("model" to buildJsonObject { put("type", "boolean") }))))
        if (scenario == "schema-symlink" && name.endsWith("ThreadStartParams.json")) {
          val target = Path.of(System.getProperty("user.dir"), "owned-fake-schema")
          Files.writeString(target, schema.toString())
          Files.createSymbolicLink(path, target)
        } else {
          Files.writeString(path, schema.toString())
        }
      }
      if (scenario == "cleanup-failure") {
        val blocked = output.resolve("blocked")
        Files.createDirectory(blocked)
        Files.writeString(blocked.resolve("owned-file"), "safe")
        Files.setPosixFilePermissions(blocked, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"))
      }
      return
    }
    // Session-flags layer, built exactly as Codex applies `-c` overrides.
    val session = mutableMapOf<String, JsonElement>()
    command.windowed(2).filter { it[0] == "-c" }.forEach { pair ->
      val (key, raw) = pair[1].split('=', limit = 2)
      override(session, key.trim().split('.'), tomlValue(raw))
    }
    val final = "mcp_servers" in session
    val inherited = listOf("punctuation.a\"b\\c", "space and [brackets]") + if (scenario == "drift" && final) listOf("new-name") else emptyList()
    // User layer: inherited entries, with the transport real MCP servers need.
    val user = JsonObject(
      CodexIsolation.groups.associateWith { group ->
        JsonObject(inherited.associateWith { JsonObject(listOfNotNull(if (group == "mcp_servers") "command" to JsonPrimitive("fixture-mcp") else null, "enabled" to JsonPrimitive(true)).toMap()) })
      },
    )
    invalidConfig(merge(user, JsonObject(session)))?.let {
      System.err.println("Error: $it")
      kotlin.system.exitProcess(1)
    }
    if (scenario == "string-false") override(session, listOf("tools", "update_plan", "enabled"), JsonPrimitive("false"))
    fun layer(type: String, version: String, config: JsonObject, disabledReason: String? = null) = buildJsonObject {
      put("name", buildJsonObject { put("type", type) })
      put("version", "sha256:$version")
      put("config", config)
      disabledReason?.let { put("disabledReason", it) }
    }
    // Highest precedence first, like Codex: a legacy managed file outranks session flags.
    val layers = listOfNotNull(
      when (scenario) {
        "managed-tools-enabled" -> layer("legacyManagedConfigTomlFromFile", "managed", buildJsonObject { put("tools", buildJsonObject { put("update_plan", buildJsonObject { put("enabled", true) }) }) })
        "managed-tools-table" -> layer("legacyManagedConfigTomlFromFile", "managed", buildJsonObject { put("tools", buildJsonObject { put("update_plan", true) }) })
        "disabled-layer-first" -> layer("project", "project", buildJsonObject { put("tools", buildJsonObject { put("update_plan", buildJsonObject { put("enabled", true) }) }) }, "untrusted project")
        else -> null
      },
      layer("sessionFlags", "session", JsonObject(session)),
      layer("user", "user", user),
    )
    val enabledLayers = layers.filter { it["disabledReason"] == null }
    val origins = mutableMapOf<String, JsonElement>()
    enabledLayers.asReversed().forEach { entry ->
      leaves(entry.getValue("config").jsonObject).forEach { path ->
        origins.keys.removeAll { it.startsWith("$path.") || path.startsWith("$it.") }
        origins[path] = buildJsonObject {
          put("name", entry.getValue("name"))
          put("version", entry.getValue("version"))
        }
      }
    }
    if (scenario == "origin-mismatch") {
      origins["tools.update_plan.enabled"] = buildJsonObject {
        put("name", buildJsonObject { put("type", "user") })
        put("version", "sha256:user")
      }
    }
    val config = enabledLayers.asReversed().map { it.getValue("config").jsonObject }.reduce(::merge).toMutableMap()
    // The typed config/read schema keeps only `tools.web_search`.
    (config["tools"] as? JsonObject)?.let { tools -> config["tools"] = JsonObject(tools.filterKeys { it == "web_search" }) }
    if (scenario == "typed-key-missing") config.remove("sandbox_mode")
    if (scenario == "denied-policy") config["sandbox_mode"] = JsonPrimitive("workspace-write")
    // A higher-precedence managed layer can still win over session flags.
    if (scenario == "redirected-chatgpt-origin") config["chatgpt_base_url"] = JsonPrimitive("https://attacker.example/backend-api/")
    if (scenario == "redirected-openai-origin") config["openai_base_url"] = JsonPrimitive("https://attacker.example/v1")
    val emptyCwd = Files.list(Path.of(".")).use { !it.findAny().isPresent }
    if (!emptyCwd || REMOVED_ENVIRONMENT.any { System.getenv(it) != null }) error("unsafe child launch")
    var threadCounter = 0
    var activeThread: String? = null
    var activeTurn: String? = null
    var capturedThread = JsonObject(emptyMap())
    var initialized = false
    var notified = false
    var read = false
    generateSequence { readlnOrNull() }.forEach { line ->
      val frame = Json.parseToJsonElement(line).jsonObject
      record(frame)
      val method = frame["method"]?.jsonPrimitive?.content ?: return@forEach
      if (scenario == "timeout" || scenario == "stubborn" || (scenario == "final-timeout" && final)) Thread.sleep(60_000)
      if (scenario == "bootstrap-eof" || (scenario == "final-eof" && final)) return
      if (scenario == "callback" || scenario == "callback-unknown") {
        println(
          buildJsonObject {
            put("id", 77)
            put("method", if (scenario == "callback-unknown") "unknown/execution" else "account/chatgptAuthTokens/refresh")
            put("params", JsonObject(emptyMap()))
          },
        )
        return@forEach
      }
      if (method == "initialize" && scenario == "delta-flood") {
        repeat(100) {
          println(
            buildJsonObject {
              put("method", "item/agentMessage/delta")
              put("params", JsonObject(emptyMap()))
            },
          )
        }
      }
      if (method == "initialize" && scenario == "relevant-flood") {
        repeat(65) {
          println(
            buildJsonObject {
              put("method", "rawResponseItem/completed")
              put("params", JsonObject(emptyMap()))
            },
          )
        }
      }
      if (method == "initialize" && scenario == "rerouted") {
        println(
          buildJsonObject {
            put("method", "model/rerouted")
            put("params", JsonObject(emptyMap()))
          },
        )
      }
      val result = when (method) {
        "initialize" -> {
          if (initialized) error("duplicate initialize")
          val capabilities = frame.getValue("params").jsonObject.getValue("capabilities").jsonObject
          if (capabilities["experimentalApi"] != JsonPrimitive(true) || capabilities["explicitGatewayOauth"] != JsonPrimitive(true) || capabilities["requestAttestation"] != JsonPrimitive(false)) error("unsafe capabilities")
          receivedOptOut = capabilities["optOutNotificationMethods"] ?: kotlinx.serialization.json.JsonNull
          optedOut = if (scenario == "ignore-opt-out") emptySet() else (receivedOptOut as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
          System.getProperty("verity.fake.receipt")?.let { Files.writeString(Path.of(it), buildJsonObject { put("initializeOptOut", receivedOptOut) }.toString() + "\n", java.nio.file.StandardOpenOption.APPEND) }
          initialized = true
          JsonObject(emptyMap())
        }

        "initialized" -> {
          if (!initialized || notified) error("handshake order")
          notified = true
          return@forEach
        }

        "config/read" -> {
          if (!initialized || !notified) error("config order")
          if (!read) {
            // Real Codex queues these after initialize, so they interleave into the next response.
            if (scenario == "config-warning") connectionEvent("configWarning", buildJsonObject { put("summary", "fixture warning") })
            connectionEvent("remoteControl/status/changed", buildJsonObject { put("status", "disabled") })
          }
          read = true
          val readParams = frame["params"] as? JsonObject
          val layered = readParams?.get("includeLayers") == JsonPrimitive(true)
          if (layered && readParams.get("cwd")?.jsonPrimitive?.contentOrNull?.let { Path.of(it).toRealPath() } != Path.of("").toRealPath()) error("config/read cwd")
          buildJsonObject {
            put("config", JsonObject(config))
            if (layered) {
              if (scenario != "layers-missing") put("layers", JsonArray(layers))
              put("origins", JsonObject(origins))
            }
            put(
              "observations",
              buildJsonObject {
                put("pid", ProcessHandle.current().pid())
                put("emptyCwd", emptyCwd)
                put("removedEnvironmentAbsent", REMOVED_ENVIRONMENT.none { System.getenv(it) != null })
                put("optOutNotificationMethods", receivedOptOut)
              },
            )
          }
        }

        "account/read" -> {
          connectionEvent("account/updated", buildJsonObject { put("authMode", "chatgpt") })
          buildJsonObject {
            put(
              "account",
              if (scenario == "model-signed-out") {
                kotlinx.serialization.json.JsonNull
              } else {
                buildJsonObject {
                  put("type", if (scenario == "model-api-account") "apiKey" else "chatgpt")
                  put("email", "sk-secret-account")
                }
              },
            )
          }
        }

        "model/list" -> buildJsonObject {
          val cursor = frame["params"]?.jsonObject?.get("cursor")
          val entry = buildJsonObject {
            put("id", "gpt-6-luna")
            if (scenario != "model-no-modality") put("inputModalities", JsonArray((if (scenario == "model-text-only") listOf("text") else listOf("text", "image")).map(::JsonPrimitive)))
            if (scenario != "model-no-efforts") put("supportedReasoningEfforts", JsonArray(listOf(buildJsonObject { put("reasoningEffort", "low") })))
          }
          val entries = when {
            scenario == "model-pagination" && cursor == null -> emptyList()
            scenario == "model-cursor-loop" -> emptyList()
            scenario == "model-duplicate" -> listOf(entry, entry)
            else -> listOf(entry)
          }
          put("data", JsonArray(entries))
          if (scenario != "model-missing-cursor") put("nextCursor", if ((scenario == "model-pagination" && cursor == null) || scenario == "model-cursor-loop") JsonPrimitive("page-two") else kotlinx.serialization.json.JsonNull)
        }

        "thread/start" -> {
          if (activeThread != null) error("overlapping model requests")
          val params = frame.getValue("params").jsonObject
          val threadLayer = session.toMutableMap()
          (params["config"] as? JsonObject)?.forEach { (key, value) -> override(threadLayer, key.split('.'), value) }
          if (invalidConfig(merge(user, JsonObject(threadLayer))) != null) {
            println(
              buildJsonObject {
                put("id", frame.getValue("id"))
                put("error", buildJsonObject { put("code", -32603) })
              },
            )
            return@forEach
          }
          capturedThread = params
          ++threadCounter
          activeThread = if (scenario == "model-reused-thread-after-success") "thread-1" else "thread-$threadCounter"
          val thread = thread(activeThread, params)
          if (scenario.startsWith("model-preack-thread-")) {
            println(
              buildJsonObject {
                put("id", "preack")
                put("method", scenario.removePrefix("model-preack-thread-"))
                put("params", JsonObject(emptyMap()))
              },
            )
          }
          event("thread/started", buildJsonObject { put("thread", thread) })
          // Real Codex reports thread settings during thread and turn start.
          event("thread/settings/updated", buildJsonObject { put("threadId", activeThread) })
          if (scenario == "model-unsolicited-turn") {
            event(
              "turn/started",
              buildJsonObject {
                put("threadId", activeThread)
                put("turn", turn("unsolicited-turn"))
              },
            )
          }
          buildJsonObject {
            put(
              "thread",
              when (scenario) {
                "model-echo-null-environments" -> JsonObject(thread + ("environments" to kotlinx.serialization.json.JsonNull))
                "model-mismatched-thread-ack" -> JsonObject(thread + ("id" to JsonPrimitive("thread-2")))
                else -> thread
              },
            )
            put("model", if (scenario == "model-echo-wrong-model") "wrong" else params.getValue("model").jsonPrimitive.content)
            put("modelProvider", "openai")
            put("cwd", params.getValue("cwd"))
            put("approvalPolicy", "never")
            put("approvalsReviewer", "user")
            put(
              "sandbox",
              buildJsonObject {
                put("type", "readOnly")
                put("networkAccess", false)
              },
            )
            put("runtimeWorkspaceRoots", JsonArray(emptyList()))
          }
        }

        "turn/start" -> {
          val params = frame.getValue("params").jsonObject
          if (params["threadId"] != JsonPrimitive(activeThread)) error("wrong thread")
          activeTurn = "turn-$threadCounter"
          if (scenario == "model-bound-raw-completed-before-turn") boundEvent("rawResponse/completed", activeThread, activeTurn)
          if (scenario.startsWith("model-preack-turn-")) {
            val type = scenario.removePrefix("model-preack-turn-")
            if (type == "raw") {
              itemEvent("rawResponseItem/completed", activeThread!!, activeTurn, "custom_tool_call_output")
            } else {
              println(
                buildJsonObject {
                  put("id", "preack")
                  put("method", type)
                  put("params", JsonObject(emptyMap()))
                },
              )
            }
          } else {
            event(
              "turn/started",
              buildJsonObject {
                put("threadId", activeThread)
                put("turn", turn(activeTurn!!))
              },
            )
          }
          buildJsonObject { put("turn", turn(activeTurn!!)) }
        }

        "turn/interrupt" -> {
          if (frame.getValue("params").jsonObject["turnId"] != JsonPrimitive(activeTurn)) error("wrong interrupt")
          if (scenario == "model-interrupt-raw-completed") boundEvent("rawResponse/completed", activeThread, activeTurn)
          JsonObject(emptyMap())
        }

        "thread/unsubscribe" -> {
          if (scenario == "model-unsubscribe-hang") Thread.sleep(60_000)
          when (scenario) {
            "model-unsubscribe-command" -> itemEvent("item/completed", activeThread!!, activeTurn!!, "commandExecution")

            "model-unsubscribe-raw" -> itemEvent("rawResponseItem/completed", activeThread!!, activeTurn!!, "function_call_output")

            "model-unsubscribe-turn-started", "model-unsubscribe-turn-failed" -> event(
              if (scenario.endsWith("started")) "turn/started" else "turn/completed",
              buildJsonObject {
                put("threadId", activeThread)
                put("turn", if (scenario.endsWith("failed")) JsonObject(turn(activeTurn!!) + ("status" to JsonPrimitive("failed"))) else turn(activeTurn!!))
              },
            )
          }
          if (frame.getValue("params").jsonObject["threadId"] != JsonPrimitive(activeThread)) error("wrong unsubscribe")
          if (scenario.startsWith("model-status-unsubscribe-")) {
            event(
              "thread/status/changed",
              buildJsonObject {
                put("threadId", activeThread)
                put(
                  "status",
                  buildJsonObject {
                    put("type", if (scenario.endsWith("notLoaded")) "notLoaded" else "active")
                    if (scenario.endsWith("approval")) put("activeFlags", JsonArray(listOf(JsonPrimitive("waitingOnApproval"))))
                  },
                )
              },
            )
          }
          activeThread = null
          activeTurn = null
          buildJsonObject { put("status", if (scenario == "model-unsubscribe-failed") "notSubscribed" else "unsubscribed") }
        }

        else -> error("Unexpected RPC: $method")
      }
      println(
        buildJsonObject {
          put("id", if (scenario == "model-wrong-rpc" && method == "turn/start") JsonPrimitive("wrong-rpc") else frame.getValue("id"))
          put("result", result)
        },
      )
      if (method == "thread/unsubscribe" && scenario == "model-idle-callback") {
        val release = Path.of(System.getProperty("verity.fake.release"))
        while (Files.readString(release) != "release") Thread.sleep(5)
        println(
          buildJsonObject {
            put("id", "idle-request")
            put("method", "unknown/request")
            put("params", JsonObject(emptyMap()))
          },
        )
      }
      if (method == "thread/start" && scenario == "model-no-stdin") Thread.sleep(60_000)
      if (method == "turn/start") {
        if (scenario.startsWith("model-callback-")) {
          val release = Path.of(System.getProperty("verity.fake.release"))
          while (Files.readString(release) != "release") Thread.sleep(5)
        }
        if (scenario == "model-ack-immediate-delta") {
          event(
            "item/agentMessage/delta",
            buildJsonObject {
              put("threadId", activeThread)
              put("turnId", activeTurn)
              put("delta", "ignore")
            },
          )
        }
        if (scenario == "model-no-stdin") Thread.sleep(60_000)
        emitModelOutput(
          scenario,
          activeThread!!,
          activeTurn!!,
          buildJsonObject {
            put("thread", capturedThread)
            put("turn", frame.getValue("params"))
            put("threadId", activeThread)
            put("turnId", activeTurn)
          },
        )
      }
      if (scenario == "no-stdin" && final && method == "config/read") Thread.sleep(60_000)
    }
    if (!read && scenario !in setOf("timeout", "final-timeout", "callback")) error("No config read")
  }

  private fun record(frame: JsonObject) {
    val path = System.getProperty("verity.fake.receipt")?.let(Path::of) ?: return
    if (frame["method"] !in listOf("account/read", "model/list", "thread/start", "turn/start", "turn/interrupt", "thread/unsubscribe").map(::JsonPrimitive) && !frame.containsKey("error")) return
    if (frame.containsKey("error") && ((frame["id"] as? JsonPrimitive)?.content?.length ?: 0) > 1024) {
      Files.writeString(
        path,
        buildJsonObject {
          put("refusalObserved", true)
          put("idLength", (frame.getValue("id") as JsonPrimitive).content.length)
        }.toString() + "\n",
        java.nio.file.StandardOpenOption.APPEND,
      )
      return
    }
    if (Files.size(path) + frame.toString().toByteArray().size > 1024 * 1024) error("fixture receipt bound")
    Files.writeString(path, frame.toString() + "\n", java.nio.file.StandardOpenOption.APPEND)
  }

  private fun thread(id: String, params: JsonObject): JsonObject = buildJsonObject {
    put("id", id)
    put("ephemeral", true)
    put("environments", JsonArray(emptyList()))
    put("model", params.getValue("model"))
    put("modelProvider", "openai")
    put("cwd", params.getValue("cwd"))
  }

  private fun turn(id: String, status: String = "inProgress") = buildJsonObject {
    put("id", id)
    put("status", status)
    put("items", JsonArray(emptyList()))
  }

  /** Informational notifications carrying the given thread (JSON null when null) and, optionally, turn. */
  private fun boundEvent(method: String, threadId: String?, turnId: String? = null) = event(
    method,
    buildJsonObject {
      put("threadId", threadId)
      turnId?.let { put("turnId", it) }
      if (method == "warning") put("message", "fixture warning")
    },
  )

  private var receivedOptOut: JsonElement = kotlinx.serialization.json.JsonNull
  private var optedOut = emptySet<String>()

  /** Connection-level notifications carry no thread identity; Codex drops them only for opted-out methods. */
  private fun connectionEvent(method: String, params: JsonObject) {
    if (method !in optedOut) event(method, params)
  }

  private fun event(method: String, params: JsonObject) {
    println(
      buildJsonObject {
        put("method", method)
        put("params", params)
      },
    )
  }

  private fun itemEvent(method: String, threadId: String, turnId: String, type: String, text: String = "reply", phase: String? = "final_answer") {
    event(
      method,
      buildJsonObject {
        put("threadId", threadId)
        put("turnId", turnId)
        put(
          "item",
          buildJsonObject {
            put("type", type)
            put("id", "item-$turnId")
            if (type == "message") {
              put("role", "assistant")
              put("content", JsonArray(emptyList()))
            }
            if (type == "reasoning") put("summary", JsonArray(emptyList()))
            if (type == "compaction") put("encrypted_content", "synthetic")
            if (type == "agentMessage") {
              put("text", text)
              phase?.let { put("phase", it) }
            }
          },
        )
      },
    )
  }

  private fun emitModelOutput(scenario: String, threadId: String, turnId: String, captured: JsonObject) {
    if (scenario == "model-hang" || scenario == "model-no-stdin") return
    if (scenario == "model-loss") kotlin.system.exitProcess(0)
    val foreign = "foreign-thread"
    when (scenario) {
      "model-bound-foreign-settings" -> boundEvent("thread/settings/updated", foreign)
      "model-bound-foreign-warning" -> boundEvent("warning", foreign)
      "model-bound-null-warning" -> boundEvent("warning", null)
      "model-bound-absent-warning" -> event("warning", buildJsonObject { put("message", "fixture") })
      "model-bound-foreign-raw-completed" -> boundEvent("rawResponse/completed", foreign, turnId)
      "model-bound-raw-completed-foreign-turn" -> boundEvent("rawResponse/completed", threadId, "foreign-turn")
      "model-bound-raw-completed-missing-turn" -> boundEvent("rawResponse/completed", threadId)
      "model-bound-mcp-status" -> boundEvent("mcpServer/startupStatus/updated", threadId)
      "model-interrupt-raw-completed" -> itemEvent("item/started", threadId, turnId, "commandExecution")
      else -> boundEvent("thread/settings/updated", threadId)
    }
    if (scenario == "model-malformed-json") {
      println("{broken")
      return
    }
    if (scenario == "model-invalid-utf8") {
      System.out.write(byteArrayOf(0xc3.toByte(), 0x28, 10))
      System.out.flush()
      return
    }
    if (scenario == "model-truncated") {
      print("{\"id\":")
      System.out.flush()
      kotlin.system.exitProcess(0)
    }
    if (scenario == "model-frame-overflow") {
      println("x".repeat(CodexAppServerClient.MAX_FRAME_BYTES + 1))
      return
    }
    if (scenario.startsWith("model-callback-")) {
      val method = when (scenario.removePrefix("model-callback-")) {
        "auth" -> "account/chatgptAuthTokens/refresh"
        "tool" -> "item/tool/call"
        else -> "unknown/request"
      }
      println(
        buildJsonObject {
          put("id", if (scenario == "model-callback-blocked") "x".repeat(4 * 1024 * 1024) else "server-request")
          put("method", method)
          put("params", JsonObject(emptyMap()))
        },
      )
    }
    if (scenario == "model-callback-blocked") Thread.sleep(60_000)
    if (scenario.startsWith("model-status-") && !scenario.startsWith("model-status-unsubscribe-")) {
      event(
        "thread/status/changed",
        buildJsonObject {
          put("threadId", threadId)
          put(
            "status",
            buildJsonObject {
              val type = scenario.removePrefix("model-status-")
              put("type", if (type.startsWith("active-")) "active" else type)
              when (type) {
                "active-empty" -> put("activeFlags", JsonArray(emptyList()))
                "active-approval" -> put("activeFlags", JsonArray(listOf(JsonPrimitive("waitingOnApproval"))))
                "active-user" -> put("activeFlags", JsonArray(listOf(JsonPrimitive("waitingOnUserInput"))))
                "active-unknown" -> put("activeFlags", JsonArray(listOf(JsonPrimitive("unknownFlag"))))
                "active-null" -> put("activeFlags", kotlinx.serialization.json.JsonNull)
              }
            },
          )
        },
      )
    }
    if (scenario.startsWith("model-normal-")) {
      val value = scenario.removePrefix("model-normal-")
      itemEvent(if (value.endsWith("-started")) "item/started" else "item/completed", threadId, turnId, value.removeSuffix("-started").removeSuffix("-completed"))
    }
    if (scenario.startsWith("model-raw-")) {
      itemEvent("rawResponseItem/completed", threadId, turnId, scenario.removePrefix("model-raw-"))
    }
    if (scenario.startsWith("model-notification-")) {
      event(
        scenario.removePrefix("model-notification-"),
        buildJsonObject {
          put("threadId", threadId)
          put("turnId", turnId)
        },
      )
    }
    if (scenario == "model-stale-after-success" && turnId == "turn-2") itemEvent("item/completed", "thread-1", "turn-1", "agentMessage", "stale previous final")
    if (scenario == "model-wrong-thread" || scenario == "model-wrong-turn" || scenario == "model-stale-turn") {
      itemEvent("item/completed", if (scenario == "model-wrong-thread") "wrong-thread" else threadId, if (scenario == "model-wrong-thread") turnId else "stale-turn", "agentMessage")
    }
    if (scenario == "model-many-events") {
      repeat(96) {
        itemEvent("item/completed", threadId, turnId, "reasoning")
        Thread.sleep(2)
      }
    }
    if (scenario == "model-stale-delta") {
      event(
        "item/agentMessage/delta",
        buildJsonObject {
          put("threadId", "stale")
          put("turnId", turnId)
          put("delta", "discarded text")
        },
      )
    }
    itemEvent("item/completed", threadId, turnId, "agentMessage", "commentary-must-not-return", "commentary")
    if (scenario != "model-commentary-only" && scenario != "model-absent") {
      if (scenario == "model-final-priority") itemEvent("item/completed", threadId, turnId, "agentMessage", "legacy-before-final", null)
      itemEvent(
        "item/completed",
        threadId,
        turnId,
        "agentMessage",
        when (scenario) {
          "model-blank" -> "  "
          "model-text-overflow" -> "é".repeat(600_000)
          "model-final-priority" -> "selected-final"
          "model-latest-final" -> "first-final"
          else -> captured.toString()
        },
        if (scenario == "model-legacy") {
          null
        } else if (scenario == "model-unknown-phase") {
          "unknown"
        } else {
          "final_answer"
        },
      )
      if (scenario == "model-final-priority") itemEvent("item/completed", threadId, turnId, "agentMessage", "legacy-after-final", null)
      if (scenario == "model-latest-final") itemEvent("item/completed", threadId, turnId, "agentMessage", "latest-final")
    }
    boundEvent("warning", threadId)
    boundEvent("rawResponse/completed", threadId, turnId)
    connectionEvent("account/rateLimits/updated", buildJsonObject { put("rateLimits", JsonObject(emptyMap())) })
    event(
      "turn/completed",
      buildJsonObject {
        put("threadId", threadId)
        put(
          "turn",
          turn(
            turnId,
            when (scenario) {
              "model-failed" -> "failed"
              "model-interrupted" -> "interrupted"
              else -> "completed"
            },
          ),
        )
      },
    )
  }

  private fun override(target: MutableMap<String, JsonElement>, path: List<String>, value: JsonElement) {
    if (path.size == 1) {
      target[path[0]] = value
      return
    }
    val child = (target[path[0]] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    override(child, path.drop(1), value)
    target[path[0]] = JsonObject(child)
  }

  private fun leaves(config: JsonObject, prefix: String = ""): List<String> = config.flatMap { (key, value) -> if (value is JsonObject && value.isNotEmpty()) leaves(value, "$prefix$key.") else listOf("$prefix$key") }

  private fun merge(base: JsonObject, overlay: JsonObject): JsonObject = JsonObject(
    base + overlay.mapValues { (key, value) ->
      val existing = base[key]
      if (existing is JsonObject && value is JsonObject) merge(existing, value) else value
    },
  )

  /** Codex's group validation: a non-table group, or an MCP entry without a transport, stops startup. */
  private fun invalidConfig(config: JsonObject): String? {
    CodexIsolation.groups.forEach { group -> if (config[group] != null && config[group] !is JsonObject) return "invalid type in `$group`" }
    return (config["mcp_servers"] as? JsonObject)?.entries?.firstOrNull { (_, entry) -> (entry as? JsonObject)?.let { "command" in it || "url" in it } != true }?.let { "invalid transport in `mcp_servers.${it.key}`" }
  }

  /** Codex parses a `-c` value as TOML, falling back to the trimmed literal string. */
  private fun tomlValue(raw: String): JsonElement = TomlSubset(raw.trim()).parse() ?: JsonPrimitive(raw.trim().trim('"', '\''))

  // Reduced, unchanged schema fragments from the retained real 0.159.0 experimental schemas.
  private val schemaFixtures: Map<String, String> = mapOf(
    "v1/InitializeParams.json" to """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","definitions":{"ClientInfo":{"properties":{"name":{"type":"string"},"title":{"type":["string","null"]},"version":{"type":"string"}},"required":["name","version"],"type":"object"},"InitializeCapabilities":{"description":"Client-declared capabilities negotiated during initialize.","properties":{"experimentalApi":{"default":false,"description":"Opt into receiving experimental API methods and fields.","type":"boolean"},"explicitGatewayOauth":{"description":"Use explicit gateway OAuth login instead of automatic browser authorization. Applies to this app-server's gateway runtime; later connections cannot undo it.","type":"boolean"},"extensions":{"additionalProperties":true,"description":"MCP extension settings declared by the app-server client.","type":["object","null"]},"mcpServerOpenaiFormElicitation":{"description":"Legacy opt-in for the `openai/form` MCP extension.\n\nNew clients should declare `openai/form` in [`Self::extensions`].","type":"boolean"},"optOutNotificationMethods":{"description":"Exact notification method names that should be suppressed for this connection (for example `thread/started`).","items":{"type":"string"},"type":["array","null"]},"requestAttestation":{"default":false,"description":"Opt into `attestation/generate` requests for upstream `x-oai-attestation`.","type":"boolean"}},"type":"object"}},"properties":{"capabilities":{"anyOf":[{"${'$'}ref":"#/definitions/InitializeCapabilities"},{"type":"null"}]},"clientInfo":{"${'$'}ref":"#/definitions/ClientInfo"}},"required":["clientInfo"],"title":"InitializeParams","type":"object"}""",
    "v2/ThreadStartParams.json" to """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","definitions":{"AbsolutePathBuf":{"description":"A path that is guaranteed to be absolute and normalized (though it is not guaranteed to be canonicalized or exist on the filesystem).\n\nIMPORTANT: When deserializing an `AbsolutePathBuf`, a base path must be set using [AbsolutePathBufGuard::new]. If no base path is set, the deserialization will fail unless the path being deserialized is already absolute.","type":"string"},"DynamicToolSpec":{"oneOf":[{"properties":{"deferLoading":{"type":"boolean"},"description":{"type":"string"},"inputSchema":true,"name":{"type":"string"},"type":{"enum":["function"],"title":"FunctionDynamicToolSpecType","type":"string"}},"required":["description","inputSchema","name","type"],"title":"FunctionDynamicToolSpec","type":"object"},{"properties":{"description":{"type":"string"},"name":{"type":"string"},"tools":{"items":{"${'$'}ref":"#/definitions/DynamicToolNamespaceTool"},"type":"array"},"type":{"enum":["namespace"],"title":"NamespaceDynamicToolSpecType","type":"string"}},"required":["description","name","tools","type"],"title":"NamespaceDynamicToolSpec","type":"object"}]},"SelectedCapabilityRoot":{"description":"A user-selected root that can expose one or more runtime capabilities.","properties":{"id":{"description":"Stable identifier supplied by the capability selection platform.","type":"string"},"location":{"allOf":[{"${'$'}ref":"#/definitions/CapabilityRootLocation"}],"description":"Where the selected root can be resolved."}},"required":["id","location"],"type":"object"},"TurnEnvironmentParams":{"properties":{"cwd":{"${'$'}ref":"#/definitions/LegacyAppPathString"},"environmentId":{"type":"string"},"runtimeWorkspaceRoots":{"description":"Environment-native runtime workspace roots. Omitted defaults to `cwd`.","items":{"${'$'}ref":"#/definitions/LegacyAppPathString"},"type":["array","null"]}},"required":["cwd","environmentId"],"type":"object"},"AskForApproval":{"oneOf":[{"enum":["untrusted","on-request","never"],"type":"string"},{"additionalProperties":false,"properties":{"granular":{"properties":{"mcp_elicitations":{"type":"boolean"},"request_permissions":{"default":false,"type":"boolean"},"rules":{"type":"boolean"},"sandbox_approval":{"type":"boolean"},"skill_approval":{"default":false,"type":"boolean"}},"required":["mcp_elicitations","rules","sandbox_approval"],"type":"object"}},"required":["granular"],"title":"GranularAskForApproval","type":"object"}]},"SandboxMode":{"enum":["read-only","workspace-write","danger-full-access"],"type":"string"},"ApprovalsReviewer":{"description":"Configures who approval requests are routed to for review. Examples include sandbox escapes, blocked network access, MCP approval prompts, and ARC escalations. Defaults to `user`. `auto_review` uses a carefully prompted subagent to gather relevant context and apply a risk-based decision framework before approving or denying the request. The legacy value `guardian_subagent` is accepted for compatibility.","enum":["user","auto_review","guardian_subagent"],"type":"string"}},"properties":{"allowProviderModelFallback":{"description":"Allow a provider with an authoritative static model catalog to replace an unavailable requested model with its default.","type":"boolean"},"dynamicTools":{"default":null,"items":{"${'$'}ref":"#/definitions/DynamicToolSpec"},"type":["array","null"]},"environments":{"description":"Optional sticky environments for this thread.\n\nOmitted selects the default environment when environment access is enabled. Empty disables environment access for turns that do not provide a turn override. Non-empty selects the first environment as the current turn environment.","items":{"${'$'}ref":"#/definitions/TurnEnvironmentParams"},"type":["array","null"]},"ephemeral":{"type":["boolean","null"]},"experimentalRawEvents":{"description":"If true, opt into emitting raw Responses API items on the event stream. This is for internal use only (e.g. Codex Cloud).","type":"boolean"},"runtimeWorkspaceRoots":{"description":"Replace the thread's runtime workspace roots. Paths must be absolute.","items":{"${'$'}ref":"#/definitions/AbsolutePathBuf"},"type":["array","null"]},"selectedCapabilityRoots":{"description":"Capability roots selected for this thread by the hosting platform.","items":{"${'$'}ref":"#/definitions/SelectedCapabilityRoot"},"type":["array","null"]},"model":{"type":["string","null"]},"modelProvider":{"type":["string","null"]},"cwd":{"type":["string","null"]},"baseInstructions":{"type":["string","null"]},"developerInstructions":{"type":["string","null"]},"config":{"additionalProperties":true,"type":["object","null"]},"approvalPolicy":{"anyOf":[{"${'$'}ref":"#/definitions/AskForApproval"},{"type":"null"}]},"approvalsReviewer":{"anyOf":[{"${'$'}ref":"#/definitions/ApprovalsReviewer"},{"type":"null"}],"description":"Override where approval requests are routed for review on this thread and subsequent turns."},"sandbox":{"anyOf":[{"${'$'}ref":"#/definitions/SandboxMode"},{"type":"null"}]}},"title":"ThreadStartParams","type":"object"}""",
    "v2/ThreadStartResponse.json" to """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","definitions":{"AbsolutePathBuf":{"description":"A path that is guaranteed to be absolute and normalized (though it is not guaranteed to be canonicalized or exist on the filesystem).\n\nIMPORTANT: When deserializing an `AbsolutePathBuf`, a base path must be set using [AbsolutePathBufGuard::new]. If no base path is set, the deserialization will fail unless the path being deserialized is already absolute.","type":"string"},"AskForApproval":{"oneOf":[{"enum":["untrusted","on-request","never"],"type":"string"},{"additionalProperties":false,"properties":{"granular":{"properties":{"mcp_elicitations":{"type":"boolean"},"request_permissions":{"default":false,"type":"boolean"},"rules":{"type":"boolean"},"sandbox_approval":{"type":"boolean"},"skill_approval":{"default":false,"type":"boolean"}},"required":["mcp_elicitations","rules","sandbox_approval"],"type":"object"}},"required":["granular"],"title":"GranularAskForApproval","type":"object"}]},"SandboxPolicy":{"oneOf":[{"properties":{"type":{"enum":["dangerFullAccess"],"title":"DangerFullAccessSandboxPolicyType","type":"string"}},"required":["type"],"title":"DangerFullAccessSandboxPolicy","type":"object"},{"properties":{"networkAccess":{"default":false,"type":"boolean"},"type":{"enum":["readOnly"],"title":"ReadOnlySandboxPolicyType","type":"string"}},"required":["type"],"title":"ReadOnlySandboxPolicy","type":"object"},{"properties":{"networkAccess":{"allOf":[{"${'$'}ref":"#/definitions/NetworkAccess"}],"default":"restricted"},"type":{"enum":["externalSandbox"],"title":"ExternalSandboxSandboxPolicyType","type":"string"}},"required":["type"],"title":"ExternalSandboxSandboxPolicy","type":"object"},{"properties":{"excludeSlashTmp":{"default":false,"type":"boolean"},"excludeTmpdirEnvVar":{"default":false,"type":"boolean"},"networkAccess":{"default":false,"type":"boolean"},"type":{"enum":["workspaceWrite"],"title":"WorkspaceWriteSandboxPolicyType","type":"string"},"writableRoots":{"default":[],"items":{"${'$'}ref":"#/definitions/AbsolutePathBuf"},"type":"array"}},"required":["type"],"title":"WorkspaceWriteSandboxPolicy","type":"object"}]},"Thread":{"properties":{"agentNickname":{"description":"Optional random unique nickname assigned to an AgentControl-spawned sub-agent.","type":["string","null"]},"agentRole":{"description":"Optional role (agent_role) assigned to an AgentControl-spawned sub-agent.","type":["string","null"]},"canAcceptDirectInput":{"description":"Whether the app server accepts direct turn input for this loaded thread. `None` means the capability is unavailable, such as for an unloaded stored thread.","type":["boolean","null"]},"cliVersion":{"description":"Version of the CLI that created the thread.","type":"string"},"createdAt":{"description":"Unix timestamp (in seconds) when the thread was created.","format":"int64","type":"integer"},"cwd":{"allOf":[{"${'$'}ref":"#/definitions/AbsolutePathBuf"}],"description":"Working directory captured for the thread."},"daybreakEnabled":{"description":"Saved Daybreak choice, independent of turn execution. Null if unset.","type":["boolean","null"]},"environments":{"default":null,"description":"Current environments for a loaded thread, in priority order, primary first. `null` means the thread is not loaded or the server does not expose its selection. An empty list means no environments are selected. This does not report connection status.","items":{"${'$'}ref":"#/definitions/ThreadEnvironment"},"type":["array","null"]},"ephemeral":{"description":"Whether the thread is ephemeral and should not be materialized on disk.","type":"boolean"},"extra":{"anyOf":[{"${'$'}ref":"#/definitions/ThreadExtra"},{"type":"null"}],"description":"Optional implementation-specific thread data."},"forkedFromId":{"description":"Source thread id when this thread was created by forking another thread.","type":["string","null"]},"gitInfo":{"anyOf":[{"${'$'}ref":"#/definitions/GitInfo"},{"type":"null"}],"description":"Optional Git metadata captured when the thread was created."},"historyMode":{"allOf":[{"${'$'}ref":"#/definitions/ThreadHistoryMode"}],"default":"legacy","description":"Persisted thread history contract selected when this thread was created."},"id":{"description":"Identifier for this thread. Codex-generated thread IDs are UUIDv7.","type":"string"},"model":{"description":"Current configured model when loaded, otherwise the latest persisted model. Null when unavailable. This is not per-turn execution telemetry.","type":["string","null"]},"modelProvider":{"description":"Model provider used for this thread (for example, 'openai').","type":"string"},"name":{"description":"Optional user-facing thread title.","type":["string","null"]},"originator":{"description":"Originator recorded when the thread was created, independent of its current client or executor. Null when the recorded originator is unavailable.","type":["string","null"]},"parentThreadId":{"description":"The ID of the parent thread. This will only be set if this thread is a subagent.","type":["string","null"]},"path":{"description":"[UNSTABLE] Path to the thread on disk.","type":["string","null"]},"preview":{"description":"Usually the first user message in the thread, if available.","type":"string"},"projectId":{"description":"Canonical project assignment owned by app-server, if any.","type":["string","null"]},"reasoningEffort":{"anyOf":[{"${'$'}ref":"#/definitions/ReasoningEffort"},{"type":"null"}],"description":"Current configured reasoning effort when loaded, otherwise the latest persisted effort. Null when unset or unavailable. This is not per-turn execution telemetry."},"recencyAt":{"description":"Unix timestamp (in seconds) used for thread recency ordering.","format":"int64","type":["integer","null"]},"section":{"anyOf":[{"${'$'}ref":"#/definitions/ThreadSection"},{"type":"null"}],"default":null,"description":"The independently persisted section selected for this thread, if any."},"sectionEnteredAt":{"default":null,"description":"Unix timestamp in seconds when the thread entered its current section.","format":"int64","type":["integer","null"]},"sessionId":{"description":"Session id shared by threads that belong to the same session tree.","type":"string"},"source":{"allOf":[{"${'$'}ref":"#/definitions/SessionSource"}],"description":"Origin of the thread (CLI, VSCode, codex exec, codex app-server, etc.)."},"status":{"allOf":[{"${'$'}ref":"#/definitions/ThreadStatus"}],"description":"Current runtime status for the thread."},"threadSource":{"anyOf":[{"${'$'}ref":"#/definitions/ThreadSource"},{"type":"null"}],"description":"Optional analytics source classification for this thread."},"turns":{"description":"Only populated on `thread/resume`, `thread/fork`, and `thread/read` (when `includeTurns` is true) responses. For all other responses and notifications returning a Thread, the turns field will be an empty list.","items":{"${'$'}ref":"#/definitions/Turn"},"type":"array"},"updatedAt":{"description":"Unix timestamp (in seconds) when the thread was last updated.","format":"int64","type":"integer"}},"required":["cliVersion","createdAt","cwd","ephemeral","id","modelProvider","preview","projectId","sessionId","source","status","turns","updatedAt"],"type":"object"},"ApprovalsReviewer":{"description":"Configures who approval requests are routed to for review. Examples include sandbox escapes, blocked network access, MCP approval prompts, and ARC escalations. Defaults to `user`. `auto_review` uses a carefully prompted subagent to gather relevant context and apply a risk-based decision framework before approving or denying the request. The legacy value `guardian_subagent` is accepted for compatibility.","enum":["user","auto_review","guardian_subagent"],"type":"string"}},"properties":{"approvalPolicy":{"${'$'}ref":"#/definitions/AskForApproval"},"cwd":{"${'$'}ref":"#/definitions/AbsolutePathBuf"},"model":{"type":"string"},"modelProvider":{"type":"string"},"runtimeWorkspaceRoots":{"default":[],"description":"Thread-scoped runtime workspace roots used to materialize `:workspace_roots`.","items":{"${'$'}ref":"#/definitions/AbsolutePathBuf"},"type":"array"},"sandbox":{"allOf":[{"${'$'}ref":"#/definitions/SandboxPolicy"}],"description":"Legacy sandbox policy retained for compatibility. Experimental clients should prefer `activePermissionProfile` for profile provenance."},"thread":{"${'$'}ref":"#/definitions/Thread"},"approvalsReviewer":{"allOf":[{"${'$'}ref":"#/definitions/ApprovalsReviewer"}],"description":"Reviewer currently used for approval requests on this thread."}},"required":["approvalPolicy","approvalsReviewer","cwd","model","modelProvider","sandbox","thread"],"title":"ThreadStartResponse","type":"object"}""",
    "v2/RawResponseItemCompletedNotification.json" to """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","definitions":{"AgentMessageInputContent":{"oneOf":[{"properties":{"text":{"type":"string"},"type":{"enum":["input_text"],"title":"InputTextAgentMessageInputContentType","type":"string"}},"required":["text","type"],"title":"InputTextAgentMessageInputContent","type":"object"},{"properties":{"encrypted_content":{"type":"string"},"type":{"enum":["encrypted_content"],"title":"EncryptedContentAgentMessageInputContentType","type":"string"}},"required":["encrypted_content","type"],"title":"EncryptedContentAgentMessageInputContent","type":"object"}]},"ConfigurationReasoning":{"description":"Reasoning settings interpreted by the backend for the routed model.","properties":{"effort":{"${'$'}ref":"#/definitions/ReasoningEffort"}},"required":["effort"],"type":"object"},"ContentItem":{"oneOf":[{"properties":{"text":{"type":"string"},"type":{"enum":["input_text"],"title":"InputTextContentItemType","type":"string"}},"required":["text","type"],"title":"InputTextContentItem","type":"object"},{"anyOf":[{"properties":{"image_url":{"type":"string"}},"required":["image_url"],"title":"ImageUrlContentItem","type":"object"},{"properties":{"file_id":{"type":"string"}},"required":["file_id"],"title":"FileIdContentItem","type":"object"}],"properties":{"detail":{"anyOf":[{"${'$'}ref":"#/definitions/ImageDetail"},{"type":"null"}]},"type":{"enum":["input_image"],"title":"InputImageContentItemType","type":"string"}},"required":["type"],"title":"InputImageContentItem","type":"object"},{"properties":{"audio_url":{"type":"string"},"type":{"enum":["input_audio"],"title":"InputAudioContentItemType","type":"string"}},"required":["audio_url","type"],"title":"InputAudioContentItem","type":"object"},{"properties":{"text":{"type":"string"},"type":{"enum":["output_text"],"title":"OutputTextContentItemType","type":"string"}},"required":["text","type"],"title":"OutputTextContentItem","type":"object"}]},"FunctionCallOutputBody":{"anyOf":[{"type":"string"},{"items":{"${'$'}ref":"#/definitions/FunctionCallOutputContentItem"},"type":"array"}]},"FunctionCallOutputContentItem":{"description":"Responses API compatible content items that can be returned by a tool call. This is a subset of ContentItem with the types we support as function call outputs.","oneOf":[{"properties":{"text":{"type":"string"},"type":{"enum":["input_text"],"title":"InputTextFunctionCallOutputContentItemType","type":"string"}},"required":["text","type"],"title":"InputTextFunctionCallOutputContentItem","type":"object"},{"anyOf":[{"properties":{"image_url":{"type":"string"}},"required":["image_url"],"title":"ImageUrlFunctionCallOutputContentItem","type":"object"},{"properties":{"file_id":{"type":"string"}},"required":["file_id"],"title":"FileIdFunctionCallOutputContentItem","type":"object"}],"properties":{"detail":{"anyOf":[{"${'$'}ref":"#/definitions/ImageDetail"},{"type":"null"}]},"type":{"enum":["input_image"],"title":"InputImageFunctionCallOutputContentItemType","type":"string"}},"required":["type"],"title":"InputImageFunctionCallOutputContentItem","type":"object"},{"properties":{"audio_url":{"type":"string"},"type":{"enum":["input_audio"],"title":"InputAudioFunctionCallOutputContentItemType","type":"string"}},"required":["audio_url","type"],"title":"InputAudioFunctionCallOutputContentItem","type":"object"},{"properties":{"encrypted_content":{"type":"string"},"type":{"enum":["encrypted_content"],"title":"EncryptedContentFunctionCallOutputContentItemType","type":"string"}},"required":["encrypted_content","type"],"title":"EncryptedContentFunctionCallOutputContentItem","type":"object"}]},"ImageDetail":{"enum":["auto","low","high","original"],"type":"string"},"InternalChatMessageMetadataPassthrough":{"description":"Internal Responses API passthrough metadata copied into underlying chat messages.\n\nResponses API strongly types this payload. Do not modify it without first getting API approval and making the corresponding Responses API change.","properties":{"turn_id":{"type":["string","null"]}},"type":"object"},"LocalShellAction":{"oneOf":[{"properties":{"command":{"items":{"type":"string"},"type":"array"},"env":{"additionalProperties":{"type":"string"},"type":["object","null"]},"timeout_ms":{"format":"uint64","minimum":0.0,"type":["integer","null"]},"type":{"enum":["exec"],"title":"ExecLocalShellActionType","type":"string"},"user":{"type":["string","null"]},"working_directory":{"type":["string","null"]}},"required":["command","type"],"title":"ExecLocalShellAction","type":"object"}]},"LocalShellStatus":{"enum":["completed","in_progress","incomplete"],"type":"string"},"MessagePhase":{"description":"Classifies an assistant message as interim commentary or final answer text.\n\nProviders do not emit this consistently, so callers must treat `None` as \"phase unknown\" and keep compatibility behavior for legacy models.","oneOf":[{"description":"Mid-turn assistant text (for example preamble/progress narration).\n\nAdditional tool calls or assistant output may follow before turn completion.","enum":["commentary"],"type":"string"},{"description":"The assistant's terminal answer text for the current turn.","enum":["final_answer"],"type":"string"}]},"ReasoningEffort":{"description":"A non-empty reasoning effort value advertised by the model.","minLength":1,"type":"string"},"ReasoningItemContent":{"oneOf":[{"properties":{"text":{"type":"string"},"type":{"enum":["reasoning_text"],"title":"ReasoningTextReasoningItemContentType","type":"string"}},"required":["text","type"],"title":"ReasoningTextReasoningItemContent","type":"object"},{"properties":{"text":{"type":"string"},"type":{"enum":["text"],"title":"TextReasoningItemContentType","type":"string"}},"required":["text","type"],"title":"TextReasoningItemContent","type":"object"}]},"ReasoningItemReasoningSummary":{"oneOf":[{"properties":{"text":{"type":"string"},"type":{"enum":["summary_text"],"title":"SummaryTextReasoningItemReasoningSummaryType","type":"string"}},"required":["text","type"],"title":"SummaryTextReasoningItemReasoningSummary","type":"object"}]},"ResponseItem":{"oneOf":[{"properties":{"content":{"items":{"${'$'}ref":"#/definitions/ContentItem"},"type":"array"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"phase":{"anyOf":[{"${'$'}ref":"#/definitions/MessagePhase"},{"type":"null"}]},"role":{"type":"string"},"type":{"enum":["message"],"title":"MessageResponseItemType","type":"string"}},"required":["content","role","type"],"title":"MessageResponseItem","type":"object"},{"properties":{"author":{"type":"string"},"content":{"items":{"${'$'}ref":"#/definitions/AgentMessageInputContent"},"type":"array"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"recipient":{"type":"string"},"type":{"enum":["agent_message"],"title":"AgentMessageResponseItemType","type":"string"}},"required":["author","content","recipient","type"],"title":"AgentMessageResponseItem","type":"object"},{"properties":{"content":{"default":null,"items":{"${'$'}ref":"#/definitions/ReasoningItemContent"},"type":["array","null"]},"encrypted_content":{"type":["string","null"]},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"summary":{"items":{"${'$'}ref":"#/definitions/ReasoningItemReasoningSummary"},"type":"array"},"type":{"enum":["reasoning"],"title":"ReasoningResponseItemType","type":"string"}},"required":["summary","type"],"title":"ReasoningResponseItem","type":"object"},{"properties":{"action":{"${'$'}ref":"#/definitions/LocalShellAction"},"call_id":{"description":"Set when using the Responses API.","type":["string","null"]},"id":{"description":"Legacy id field retained for compatibility with older payloads.","type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"status":{"${'$'}ref":"#/definitions/LocalShellStatus"},"type":{"enum":["local_shell_call"],"title":"LocalShellCallResponseItemType","type":"string"}},"required":["action","status","type"],"title":"LocalShellCallResponseItem","type":"object"},{"properties":{"arguments":{"type":"string"},"call_id":{"type":"string"},"encrypted_function_args":{"items":{"type":"string"},"type":["array","null"]},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"name":{"type":"string"},"namespace":{"type":["string","null"]},"type":{"enum":["function_call"],"title":"FunctionCallResponseItemType","type":"string"}},"required":["arguments","call_id","name","type"],"title":"FunctionCallResponseItem","type":"object"},{"properties":{"arguments":true,"call_id":{"type":["string","null"]},"execution":{"type":"string"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"status":{"type":["string","null"]},"type":{"enum":["tool_search_call"],"title":"ToolSearchCallResponseItemType","type":"string"}},"required":["arguments","execution","type"],"title":"ToolSearchCallResponseItem","type":"object"},{"properties":{"call_id":{"type":["string","null"]},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"name":{"type":["string","null"]},"namespace":{"type":["string","null"]},"output":{"${'$'}ref":"#/definitions/FunctionCallOutputBody"},"type":{"enum":["function_call_output"],"title":"FunctionCallOutputResponseItemType","type":"string"}},"required":["output","type"],"title":"FunctionCallOutputResponseItem","type":"object"},{"properties":{"call_id":{"type":"string"},"id":{"type":["string","null"]},"input":{"type":"string"},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"name":{"type":"string"},"namespace":{"type":["string","null"]},"status":{"type":["string","null"]},"type":{"enum":["custom_tool_call"],"title":"CustomToolCallResponseItemType","type":"string"}},"required":["call_id","input","name","type"],"title":"CustomToolCallResponseItem","type":"object"},{"properties":{"call_id":{"type":"string"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"name":{"type":["string","null"]},"output":{"${'$'}ref":"#/definitions/FunctionCallOutputBody"},"type":{"enum":["custom_tool_call_output"],"title":"CustomToolCallOutputResponseItemType","type":"string"}},"required":["call_id","output","type"],"title":"CustomToolCallOutputResponseItem","type":"object"},{"properties":{"call_id":{"type":["string","null"]},"execution":{"type":"string"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"status":{"type":"string"},"tools":{"items":true,"type":"array"},"type":{"enum":["tool_search_output"],"title":"ToolSearchOutputResponseItemType","type":"string"}},"required":["execution","status","tools","type"],"title":"ToolSearchOutputResponseItem","type":"object"},{"properties":{"action":{"anyOf":[{"${'$'}ref":"#/definitions/ResponsesApiWebSearchAction"},{"type":"null"}]},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"status":{"type":["string","null"]},"type":{"enum":["web_search_call"],"title":"WebSearchCallResponseItemType","type":"string"}},"required":["type"],"title":"WebSearchCallResponseItem","type":"object"},{"properties":{"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"result":{"type":"string"},"revised_prompt":{"type":["string","null"]},"status":{"type":"string"},"type":{"enum":["image_generation_call"],"title":"ImageGenerationCallResponseItemType","type":"string"}},"required":["result","status","type"],"title":"ImageGenerationCallResponseItem","type":"object"},{"properties":{"encrypted_content":{"type":"string"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"type":{"enum":["compaction"],"title":"CompactionResponseItemType","type":"string"}},"required":["encrypted_content","type"],"title":"CompactionResponseItem","type":"object"},{"description":"A durable input control interpreted by the backend at its position in history.","properties":{"reasoning":{"${'$'}ref":"#/definitions/ConfigurationReasoning"},"type":{"enum":["configuration_update"],"title":"ConfigurationUpdateResponseItemType","type":"string"}},"required":["reasoning","type"],"title":"ConfigurationUpdateResponseItem","type":"object"},{"properties":{"type":{"enum":["compaction_trigger"],"title":"CompactionTriggerResponseItemType","type":"string"}},"required":["type"],"title":"CompactionTriggerResponseItem","type":"object"},{"properties":{"encrypted_content":{"type":["string","null"]},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"type":{"enum":["context_compaction"],"title":"ContextCompactionResponseItemType","type":"string"}},"required":["type"],"title":"ContextCompactionResponseItem","type":"object"},{"properties":{"type":{"enum":["other"],"title":"OtherResponseItemType","type":"string"}},"required":["type"],"title":"OtherResponseItem","type":"object"}]},"ResponsesApiWebSearchAction":{"oneOf":[{"properties":{"queries":{"items":{"type":"string"},"type":["array","null"]},"query":{"type":["string","null"]},"type":{"enum":["search"],"title":"SearchResponsesApiWebSearchActionType","type":"string"}},"required":["type"],"title":"SearchResponsesApiWebSearchAction","type":"object"},{"properties":{"type":{"enum":["open_page"],"title":"OpenPageResponsesApiWebSearchActionType","type":"string"},"url":{"type":["string","null"]}},"required":["type"],"title":"OpenPageResponsesApiWebSearchAction","type":"object"},{"properties":{"pattern":{"type":["string","null"]},"type":{"enum":["find_in_page"],"title":"FindInPageResponsesApiWebSearchActionType","type":"string"},"url":{"type":["string","null"]}},"required":["type"],"title":"FindInPageResponsesApiWebSearchAction","type":"object"},{"properties":{"type":{"enum":["other"],"title":"OtherResponsesApiWebSearchActionType","type":"string"}},"required":["type"],"title":"OtherResponsesApiWebSearchAction","type":"object"}]}},"properties":{"item":{"${'$'}ref":"#/definitions/ResponseItem"},"threadId":{"type":"string"},"turnId":{"type":"string"}},"required":["item","threadId","turnId"],"title":"RawResponseItemCompletedNotification","type":"object"}""",
    "v2/TurnStartParams.json" to """{"properties":{"additionalContext":{"additionalProperties":{"${'$'}ref":"#/definitions/AdditionalContextEntry"},"description":"Optional client-provided context fragments keyed by an opaque source identifier.","type":["object","null"]},"approvalPolicy":{"anyOf":[{"${'$'}ref":"#/definitions/AskForApproval"},{"type":"null"}],"description":"Override the approval policy for this turn and subsequent turns."},"approvalsReviewer":{"anyOf":[{"${'$'}ref":"#/definitions/ApprovalsReviewer"},{"type":"null"}],"description":"Override where approval requests are routed for review on this turn and subsequent turns."},"clientUserMessageId":{"type":["string","null"]},"collaborationMode":{"anyOf":[{"${'$'}ref":"#/definitions/CollaborationMode"},{"type":"null"}],"description":"EXPERIMENTAL - Set a pre-set collaboration mode. Takes precedence over model, reasoning_effort, and developer instructions if set.\n\nFor `collaboration_mode.settings.developer_instructions`, `null` means \"use the built-in instructions for the selected mode\"."},"cwd":{"description":"Override the working directory for this turn and subsequent turns.","type":["string","null"]},"cyberAccessProgram":{"anyOf":[{"${'$'}ref":"#/definitions/CyberAccessProgram"},{"type":"null"}],"description":"EXPERIMENTAL - Request a workspace-authorized cyber program for this turn. Omission preserves automatic behavior. This does not grant access."},"disabledPluginIds":{"description":"Replace this thread's disabled plugin IDs. Omitted/null preserves the list; [] clears it.","items":{"type":"string"},"type":["array","null"]},"effort":{"anyOf":[{"${'$'}ref":"#/definitions/ReasoningEffort"},{"type":"null"}],"description":"Override the reasoning effort for this turn and subsequent turns."},"environments":{"description":"Optional environments for this turn and subsequent turns.\n\nOmitted uses the thread sticky environments. Empty disables environment access for this turn. Non-empty selects the first environment as the current turn environment for this turn.","items":{"${'$'}ref":"#/definitions/TurnEnvironmentParams"},"type":["array","null"]},"input":{"items":{"${'$'}ref":"#/definitions/UserInput"},"type":"array"},"model":{"description":"Override the model for this turn and subsequent turns.","type":["string","null"]},"multiAgentMode":{"anyOf":[{"${'$'}ref":"#/definitions/MultiAgentMode"},{"type":"null"}],"description":"@deprecated Ignored. Use `effort: \"ultra\"` for proactive multi-agent behavior."},"outputSchema":{"description":"Optional JSON Schema used to constrain the final assistant message for this turn."},"permissions":{"description":"Select a named permissions profile id for this turn and subsequent turns. Cannot be combined with `sandboxPolicy`.","type":["string","null"]},"personality":{"anyOf":[{"${'$'}ref":"#/definitions/Personality"},{"type":"null"}],"description":"@deprecated `friendly` and `pragmatic` no longer select a style. Changing this does not rewrite the thread's existing instructions."},"responsesapiClientMetadata":{"additionalProperties":{"type":"string"},"description":"Optional metadata to enrich Codex's ResponsesAPI turn metadata.\n\nEntries are flattened into the JSON string sent as `client_metadata[\"x-codex-turn-metadata\"]` on ResponsesAPI HTTP and websocket requests.\n\nThey are not sent as top-level ResponsesAPI `client_metadata` keys, and reserved keys such as `session_id`, `thread_id`, `turn_id`, and `window_id` cannot be overridden.","type":["object","null"]},"runtimeWorkspaceRoots":{"description":"Replace the thread's runtime workspace roots for this turn and subsequent turns. Paths must be absolute.","items":{"${'$'}ref":"#/definitions/AbsolutePathBuf"},"type":["array","null"]},"sandboxPolicy":{"anyOf":[{"${'$'}ref":"#/definitions/SandboxPolicy"},{"type":"null"}],"description":"Override the sandbox policy for this turn and subsequent turns."},"serviceTier":{"description":"Override the service tier for this turn and subsequent turns.","type":["string","null"]},"serviceTierForTurn":{"description":"Override the service tier only when this request starts a new turn. Use \"default\" for standard speed. Omitted or null inherits the thread's tier. Does not change the thread's tier or a turn being steered.","type":["string","null"]},"summary":{"anyOf":[{"${'$'}ref":"#/definitions/ReasoningSummary"},{"type":"null"}],"description":"Override the reasoning summary for this turn and subsequent turns."},"threadId":{"type":"string"},"toolOutput":{"anyOf":[{"${'$'}ref":"#/definitions/TurnToolOutput"},{"type":"null"}]},"turnTrigger":{"description":"Optional source classification for the caller that starts this turn. Ignored when this request steers an already-active turn.","type":["string","null"]}},"required":["input","threadId"],"type":"object","definitions":{"UserInput":{"oneOf":[{"properties":{"text":{"type":"string"},"text_elements":{"default":[],"description":"UI-defined spans within `text` used to render or persist special elements.","items":{"${'$'}ref":"#/definitions/TextElement"},"type":"array"},"type":{"enum":["text"],"title":"TextUserInputType","type":"string"}},"required":["text","type"],"title":"TextUserInput","type":"object"},{"anyOf":[{"properties":{"url":{"type":"string"}},"required":["url"],"title":"UrlUserInput","type":"object"},{"properties":{"fileId":{"type":"string"}},"required":["fileId"],"title":"FileIdUserInput","type":"object"}],"properties":{"detail":{"anyOf":[{"${'$'}ref":"#/definitions/ImageDetail"},{"type":"null"}],"default":null},"type":{"enum":["image"],"title":"ImageUserInputType","type":"string"}},"required":["type"],"title":"ImageUserInput","type":"object"},{"properties":{"detail":{"anyOf":[{"${'$'}ref":"#/definitions/ImageDetail"},{"type":"null"}],"default":null},"path":{"type":"string"},"type":{"enum":["localImage"],"title":"LocalImageUserInputType","type":"string"}},"required":["path","type"],"title":"LocalImageUserInput","type":"object"},{"properties":{"type":{"enum":["audio"],"title":"AudioUserInputType","type":"string"},"url":{"type":"string"}},"required":["type","url"],"title":"AudioUserInput","type":"object"},{"properties":{"path":{"type":"string"},"type":{"enum":["localAudio"],"title":"LocalAudioUserInputType","type":"string"}},"required":["path","type"],"title":"LocalAudioUserInput","type":"object"},{"properties":{"name":{"type":"string"},"path":{"type":"string"},"type":{"enum":["skill"],"title":"SkillUserInputType","type":"string"}},"required":["name","path","type"],"title":"SkillUserInput","type":"object"},{"properties":{"name":{"type":"string"},"path":{"type":"string"},"type":{"enum":["mention"],"title":"MentionUserInputType","type":"string"}},"required":["name","path","type"],"title":"MentionUserInput","type":"object"}]},"ReasoningEffort":{"description":"A non-empty reasoning effort value advertised by the model.","minLength":1,"type":"string"}}}""",
    "v2/TurnStartResponse.json" to """{"properties":{"turn":{"${'$'}ref":"#/definitions/Turn"}},"required":["turn"],"type":"object","definitions":{"Turn":{"properties":{"completedAt":{"description":"Unix timestamp (in seconds) when the turn completed.","format":"int64","type":["integer","null"]},"durationMs":{"description":"Duration between turn start and completion in milliseconds, if known.","format":"int64","type":["integer","null"]},"error":{"anyOf":[{"${'$'}ref":"#/definitions/TurnError"},{"type":"null"}],"description":"Error associated with a failed or interrupted turn."},"id":{"description":"Identifier for this turn. Codex-generated turn IDs are UUIDv7.","type":"string"},"items":{"description":"Thread items currently included in this turn payload.","items":{"${'$'}ref":"#/definitions/ThreadItem"},"type":"array"},"itemsView":{"allOf":[{"${'$'}ref":"#/definitions/TurnItemsView"}],"default":"full","description":"Describes how much of `items` has been loaded for this turn."},"startedAt":{"description":"Unix timestamp (in seconds) when the turn started.","format":"int64","type":["integer","null"]},"status":{"${'$'}ref":"#/definitions/TurnStatus"}},"required":["id","items","status"],"type":"object"},"TurnStatus":{"enum":["completed","interrupted","failed","inProgress"],"type":"string"},"ThreadItem":{"oneOf":[{"properties":{"clientId":{"type":["string","null"]},"content":{"items":{"${'$'}ref":"#/definitions/UserInput"},"type":"array"},"id":{"type":"string"},"type":{"enum":["userMessage"],"title":"UserMessageThreadItemType","type":"string"}},"required":["content","id","type"],"title":"UserMessageThreadItem","type":"object"},{"properties":{"fragments":{"items":{"${'$'}ref":"#/definitions/HookPromptFragment"},"type":"array"},"id":{"type":"string"},"type":{"enum":["hookPrompt"],"title":"HookPromptThreadItemType","type":"string"}},"required":["fragments","id","type"],"title":"HookPromptThreadItem","type":"object"},{"properties":{"delivery":{"anyOf":[{"${'$'}ref":"#/definitions/AgentMessageDelivery"},{"type":"null"}],"default":null},"id":{"type":"string"},"memoryCitation":{"anyOf":[{"${'$'}ref":"#/definitions/MemoryCitation"},{"type":"null"}],"default":null},"phase":{"anyOf":[{"${'$'}ref":"#/definitions/MessagePhase"},{"type":"null"}],"default":null},"questions":{"default":null,"items":{"${'$'}ref":"#/definitions/AsyncUserInputQuestion"},"type":["array","null"]},"text":{"type":"string"},"type":{"enum":["agentMessage"],"title":"AgentMessageThreadItemType","type":"string"}},"required":["id","text","type"],"title":"AgentMessageThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"name":{"type":"string"},"namespace":{"type":["string","null"]},"output":{"${'$'}ref":"#/definitions/FunctionCallOutputBody"},"type":{"enum":["functionCallOutput"],"title":"FunctionCallOutputThreadItemType","type":"string"}},"required":["id","name","output","type"],"title":"FunctionCallOutputThreadItem","type":"object"},{"description":"EXPERIMENTAL - proposed plan item content. The completed plan item is authoritative and may not match the concatenation of `PlanDelta` text.","properties":{"id":{"type":"string"},"text":{"type":"string"},"type":{"enum":["plan"],"title":"PlanThreadItemType","type":"string"}},"required":["id","text","type"],"title":"PlanThreadItem","type":"object"},{"properties":{"content":{"default":[],"items":{"type":"string"},"type":"array"},"id":{"type":"string"},"summary":{"default":[],"items":{"type":"string"},"type":"array"},"type":{"enum":["reasoning"],"title":"ReasoningThreadItemType","type":"string"}},"required":["id","type"],"title":"ReasoningThreadItem","type":"object"},{"properties":{"aggregatedOutput":{"description":"The command's output, aggregated from stdout and stderr.","type":["string","null"]},"command":{"description":"The command to be executed.","type":"string"},"commandActions":{"description":"A best-effort parsing of the command to understand the action(s) it will perform. This returns a list of CommandAction objects because a single shell command may be composed of many commands piped together.","items":{"${'$'}ref":"#/definitions/CommandAction"},"type":"array"},"cwd":{"allOf":[{"${'$'}ref":"#/definitions/LegacyAppPathString"}],"description":"The command's working directory."},"durationMs":{"description":"The duration of the command execution in milliseconds.","format":"int64","type":["integer","null"]},"exitCode":{"description":"The command's exit code.","format":"int32","type":["integer","null"]},"id":{"type":"string"},"pluginId":{"default":null,"description":"Trusted first-party plugin id when this command resolves to one plugin script.","type":["string","null"]},"processId":{"description":"Identifier for the underlying PTY process (when available).","type":["string","null"]},"scriptPath":{"default":null,"description":"Safe plugin-relative path when this command resolves to one plugin script.","type":["string","null"]},"source":{"allOf":[{"${'$'}ref":"#/definitions/CommandExecutionSource"}],"default":"agent"},"status":{"${'$'}ref":"#/definitions/CommandExecutionStatus"},"type":{"enum":["commandExecution"],"title":"CommandExecutionThreadItemType","type":"string"}},"required":["command","commandActions","cwd","id","status","type"],"title":"CommandExecutionThreadItem","type":"object"},{"properties":{"changes":{"items":{"${'$'}ref":"#/definitions/FileUpdateChange"},"type":"array"},"id":{"type":"string"},"status":{"${'$'}ref":"#/definitions/PatchApplyStatus"},"type":{"enum":["fileChange"],"title":"FileChangeThreadItemType","type":"string"}},"required":["changes","id","status","type"],"title":"FileChangeThreadItem","type":"object"},{"properties":{"appContext":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallAppContext"},{"type":"null"}]},"arguments":true,"durationMs":{"description":"The duration of the MCP tool call in milliseconds.","format":"int64","type":["integer","null"]},"error":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallError"},{"type":"null"}]},"id":{"type":"string"},"mcpAppResourceUri":{"description":"Legacy compatibility field; prefer `mcpAppUi.resourceUri` when available.","type":["string","null"]},"mcpAppUi":{"anyOf":[{"${'$'}ref":"#/definitions/McpAppUi"},{"type":"null"}],"description":"Presentation captured from the invoked descriptor; absent in older history."},"pluginId":{"type":["string","null"]},"readOnlyHint":{"type":["boolean","null"]},"result":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallResult"},{"type":"null"}]},"server":{"type":"string"},"status":{"${'$'}ref":"#/definitions/McpToolCallStatus"},"tool":{"type":"string"},"type":{"enum":["mcpToolCall"],"title":"McpToolCallThreadItemType","type":"string"}},"required":["arguments","id","server","status","tool","type"],"title":"McpToolCallThreadItem","type":"object"},{"properties":{"arguments":true,"contentItems":{"items":{"${'$'}ref":"#/definitions/DynamicToolCallOutputContentItem"},"type":["array","null"]},"durationMs":{"description":"The duration of the dynamic tool call in milliseconds.","format":"int64","type":["integer","null"]},"id":{"type":"string"},"namespace":{"type":["string","null"]},"status":{"${'$'}ref":"#/definitions/DynamicToolCallStatus"},"success":{"type":["boolean","null"]},"tool":{"type":"string"},"type":{"enum":["dynamicToolCall"],"title":"DynamicToolCallThreadItemType","type":"string"}},"required":["arguments","id","status","tool","type"],"title":"DynamicToolCallThreadItem","type":"object"},{"properties":{"agentsStates":{"additionalProperties":{"${'$'}ref":"#/definitions/CollabAgentState"},"description":"Last known status of the target agents, when available.","type":"object"},"id":{"description":"Unique identifier for this collab tool call.","type":"string"},"model":{"description":"Model requested for the spawned agent, when applicable.","type":["string","null"]},"prompt":{"description":"Prompt text sent as part of the collab tool call, when available.","type":["string","null"]},"reasoningEffort":{"anyOf":[{"${'$'}ref":"#/definitions/ReasoningEffort"},{"type":"null"}],"description":"Reasoning effort requested for the spawned agent, when applicable."},"receiverThreadIds":{"description":"Thread ID of the receiving agent, when applicable. In case of spawn operation, this corresponds to the newly spawned agent.","items":{"type":"string"},"type":"array"},"senderThreadId":{"description":"Thread ID of the agent issuing the collab request.","type":"string"},"status":{"allOf":[{"${'$'}ref":"#/definitions/CollabAgentToolCallStatus"}],"description":"Current status of the collab tool call."},"tool":{"allOf":[{"${'$'}ref":"#/definitions/CollabAgentTool"}],"description":"Name of the collab tool that was invoked."},"type":{"enum":["collabAgentToolCall"],"title":"CollabAgentToolCallThreadItemType","type":"string"}},"required":["agentsStates","id","receiverThreadIds","senderThreadId","status","tool","type"],"title":"CollabAgentToolCallThreadItem","type":"object"},{"properties":{"agentPath":{"type":"string"},"agentThreadId":{"type":"string"},"id":{"type":"string"},"kind":{"${'$'}ref":"#/definitions/SubAgentActivityKind"},"type":{"enum":["subAgentActivity"],"title":"SubAgentActivityThreadItemType","type":"string"}},"required":["agentPath","agentThreadId","id","kind","type"],"title":"SubAgentActivityThreadItem","type":"object"},{"properties":{"action":{"anyOf":[{"${'$'}ref":"#/definitions/WebSearchAction"},{"type":"null"}]},"id":{"type":"string"},"query":{"type":"string"},"results":{"default":null,"description":"Structured search results returned out-of-band by standalone web search.\n\nThese stay as opaque JSON at the extension/app-server boundary so new result fields and result types can pass through without a Codex release.","items":true,"type":["array","null"]},"type":{"enum":["webSearch"],"title":"WebSearchThreadItemType","type":"string"}},"required":["id","query","type"],"title":"WebSearchThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"path":{"${'$'}ref":"#/definitions/LegacyAppPathString"},"type":{"enum":["imageView"],"title":"ImageViewThreadItemType","type":"string"}},"required":["id","path","type"],"title":"ImageViewThreadItem","type":"object"},{"description":"Display item emitted by the interruptible `clock.sleep` tool.","properties":{"durationMs":{"format":"uint64","minimum":0.0,"type":"integer"},"id":{"type":"string"},"type":{"enum":["sleep"],"title":"SleepThreadItemType","type":"string"}},"required":["durationMs","id","type"],"title":"SleepThreadItem","type":"object"},{"properties":{"failure":{"anyOf":[{"${'$'}ref":"#/definitions/ImageGenerationFailure"},{"type":"null"}],"default":null},"id":{"type":"string"},"result":{"type":"string"},"revisedPrompt":{"type":["string","null"]},"savedPath":{"anyOf":[{"${'$'}ref":"#/definitions/AbsolutePathBuf"},{"type":"null"}]},"status":{"type":"string"},"transparentBackground":{"default":null,"type":["boolean","null"]},"type":{"enum":["imageGeneration"],"title":"ImageGenerationThreadItemType","type":"string"}},"required":["id","result","status","type"],"title":"ImageGenerationThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"review":{"type":"string"},"type":{"enum":["enteredReviewMode"],"title":"EnteredReviewModeThreadItemType","type":"string"}},"required":["id","review","type"],"title":"EnteredReviewModeThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"review":{"type":"string"},"type":{"enum":["exitedReviewMode"],"title":"ExitedReviewModeThreadItemType","type":"string"}},"required":["id","review","type"],"title":"ExitedReviewModeThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"type":{"enum":["contextCompaction"],"title":"ContextCompactionThreadItemType","type":"string"}},"required":["id","type"],"title":"ContextCompactionThreadItem","type":"object"}]},"MessagePhase":{"description":"Classifies an assistant message as interim commentary or final answer text.\n\nProviders do not emit this consistently, so callers must treat `None` as \"phase unknown\" and keep compatibility behavior for legacy models.","oneOf":[{"description":"Mid-turn assistant text (for example preamble/progress narration).\n\nAdditional tool calls or assistant output may follow before turn completion.","enum":["commentary"],"type":"string"},{"description":"The assistant's terminal answer text for the current turn.","enum":["final_answer"],"type":"string"}]}}}""",
    "v2/ItemStartedNotification.json" to """{"properties":{"item":{"${'$'}ref":"#/definitions/ThreadItem"},"startedAtMs":{"description":"Unix timestamp (in milliseconds) when this item lifecycle started.","format":"int64","type":"integer"},"threadId":{"type":"string"},"turnId":{"type":"string"}},"required":["item","startedAtMs","threadId","turnId"],"type":"object","definitions":{"ThreadItem":{"oneOf":[{"properties":{"clientId":{"type":["string","null"]},"content":{"items":{"${'$'}ref":"#/definitions/UserInput"},"type":"array"},"id":{"type":"string"},"type":{"enum":["userMessage"],"title":"UserMessageThreadItemType","type":"string"}},"required":["content","id","type"],"title":"UserMessageThreadItem","type":"object"},{"properties":{"fragments":{"items":{"${'$'}ref":"#/definitions/HookPromptFragment"},"type":"array"},"id":{"type":"string"},"type":{"enum":["hookPrompt"],"title":"HookPromptThreadItemType","type":"string"}},"required":["fragments","id","type"],"title":"HookPromptThreadItem","type":"object"},{"properties":{"delivery":{"anyOf":[{"${'$'}ref":"#/definitions/AgentMessageDelivery"},{"type":"null"}],"default":null},"id":{"type":"string"},"memoryCitation":{"anyOf":[{"${'$'}ref":"#/definitions/MemoryCitation"},{"type":"null"}],"default":null},"phase":{"anyOf":[{"${'$'}ref":"#/definitions/MessagePhase"},{"type":"null"}],"default":null},"questions":{"default":null,"items":{"${'$'}ref":"#/definitions/AsyncUserInputQuestion"},"type":["array","null"]},"text":{"type":"string"},"type":{"enum":["agentMessage"],"title":"AgentMessageThreadItemType","type":"string"}},"required":["id","text","type"],"title":"AgentMessageThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"name":{"type":"string"},"namespace":{"type":["string","null"]},"output":{"${'$'}ref":"#/definitions/FunctionCallOutputBody"},"type":{"enum":["functionCallOutput"],"title":"FunctionCallOutputThreadItemType","type":"string"}},"required":["id","name","output","type"],"title":"FunctionCallOutputThreadItem","type":"object"},{"description":"EXPERIMENTAL - proposed plan item content. The completed plan item is authoritative and may not match the concatenation of `PlanDelta` text.","properties":{"id":{"type":"string"},"text":{"type":"string"},"type":{"enum":["plan"],"title":"PlanThreadItemType","type":"string"}},"required":["id","text","type"],"title":"PlanThreadItem","type":"object"},{"properties":{"content":{"default":[],"items":{"type":"string"},"type":"array"},"id":{"type":"string"},"summary":{"default":[],"items":{"type":"string"},"type":"array"},"type":{"enum":["reasoning"],"title":"ReasoningThreadItemType","type":"string"}},"required":["id","type"],"title":"ReasoningThreadItem","type":"object"},{"properties":{"aggregatedOutput":{"description":"The command's output, aggregated from stdout and stderr.","type":["string","null"]},"command":{"description":"The command to be executed.","type":"string"},"commandActions":{"description":"A best-effort parsing of the command to understand the action(s) it will perform. This returns a list of CommandAction objects because a single shell command may be composed of many commands piped together.","items":{"${'$'}ref":"#/definitions/CommandAction"},"type":"array"},"cwd":{"allOf":[{"${'$'}ref":"#/definitions/LegacyAppPathString"}],"description":"The command's working directory."},"durationMs":{"description":"The duration of the command execution in milliseconds.","format":"int64","type":["integer","null"]},"exitCode":{"description":"The command's exit code.","format":"int32","type":["integer","null"]},"id":{"type":"string"},"pluginId":{"default":null,"description":"Trusted first-party plugin id when this command resolves to one plugin script.","type":["string","null"]},"processId":{"description":"Identifier for the underlying PTY process (when available).","type":["string","null"]},"scriptPath":{"default":null,"description":"Safe plugin-relative path when this command resolves to one plugin script.","type":["string","null"]},"source":{"allOf":[{"${'$'}ref":"#/definitions/CommandExecutionSource"}],"default":"agent"},"status":{"${'$'}ref":"#/definitions/CommandExecutionStatus"},"type":{"enum":["commandExecution"],"title":"CommandExecutionThreadItemType","type":"string"}},"required":["command","commandActions","cwd","id","status","type"],"title":"CommandExecutionThreadItem","type":"object"},{"properties":{"changes":{"items":{"${'$'}ref":"#/definitions/FileUpdateChange"},"type":"array"},"id":{"type":"string"},"status":{"${'$'}ref":"#/definitions/PatchApplyStatus"},"type":{"enum":["fileChange"],"title":"FileChangeThreadItemType","type":"string"}},"required":["changes","id","status","type"],"title":"FileChangeThreadItem","type":"object"},{"properties":{"appContext":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallAppContext"},{"type":"null"}]},"arguments":true,"durationMs":{"description":"The duration of the MCP tool call in milliseconds.","format":"int64","type":["integer","null"]},"error":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallError"},{"type":"null"}]},"id":{"type":"string"},"mcpAppResourceUri":{"description":"Legacy compatibility field; prefer `mcpAppUi.resourceUri` when available.","type":["string","null"]},"mcpAppUi":{"anyOf":[{"${'$'}ref":"#/definitions/McpAppUi"},{"type":"null"}],"description":"Presentation captured from the invoked descriptor; absent in older history."},"pluginId":{"type":["string","null"]},"readOnlyHint":{"type":["boolean","null"]},"result":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallResult"},{"type":"null"}]},"server":{"type":"string"},"status":{"${'$'}ref":"#/definitions/McpToolCallStatus"},"tool":{"type":"string"},"type":{"enum":["mcpToolCall"],"title":"McpToolCallThreadItemType","type":"string"}},"required":["arguments","id","server","status","tool","type"],"title":"McpToolCallThreadItem","type":"object"},{"properties":{"arguments":true,"contentItems":{"items":{"${'$'}ref":"#/definitions/DynamicToolCallOutputContentItem"},"type":["array","null"]},"durationMs":{"description":"The duration of the dynamic tool call in milliseconds.","format":"int64","type":["integer","null"]},"id":{"type":"string"},"namespace":{"type":["string","null"]},"status":{"${'$'}ref":"#/definitions/DynamicToolCallStatus"},"success":{"type":["boolean","null"]},"tool":{"type":"string"},"type":{"enum":["dynamicToolCall"],"title":"DynamicToolCallThreadItemType","type":"string"}},"required":["arguments","id","status","tool","type"],"title":"DynamicToolCallThreadItem","type":"object"},{"properties":{"agentsStates":{"additionalProperties":{"${'$'}ref":"#/definitions/CollabAgentState"},"description":"Last known status of the target agents, when available.","type":"object"},"id":{"description":"Unique identifier for this collab tool call.","type":"string"},"model":{"description":"Model requested for the spawned agent, when applicable.","type":["string","null"]},"prompt":{"description":"Prompt text sent as part of the collab tool call, when available.","type":["string","null"]},"reasoningEffort":{"anyOf":[{"${'$'}ref":"#/definitions/ReasoningEffort"},{"type":"null"}],"description":"Reasoning effort requested for the spawned agent, when applicable."},"receiverThreadIds":{"description":"Thread ID of the receiving agent, when applicable. In case of spawn operation, this corresponds to the newly spawned agent.","items":{"type":"string"},"type":"array"},"senderThreadId":{"description":"Thread ID of the agent issuing the collab request.","type":"string"},"status":{"allOf":[{"${'$'}ref":"#/definitions/CollabAgentToolCallStatus"}],"description":"Current status of the collab tool call."},"tool":{"allOf":[{"${'$'}ref":"#/definitions/CollabAgentTool"}],"description":"Name of the collab tool that was invoked."},"type":{"enum":["collabAgentToolCall"],"title":"CollabAgentToolCallThreadItemType","type":"string"}},"required":["agentsStates","id","receiverThreadIds","senderThreadId","status","tool","type"],"title":"CollabAgentToolCallThreadItem","type":"object"},{"properties":{"agentPath":{"type":"string"},"agentThreadId":{"type":"string"},"id":{"type":"string"},"kind":{"${'$'}ref":"#/definitions/SubAgentActivityKind"},"type":{"enum":["subAgentActivity"],"title":"SubAgentActivityThreadItemType","type":"string"}},"required":["agentPath","agentThreadId","id","kind","type"],"title":"SubAgentActivityThreadItem","type":"object"},{"properties":{"action":{"anyOf":[{"${'$'}ref":"#/definitions/WebSearchAction"},{"type":"null"}]},"id":{"type":"string"},"query":{"type":"string"},"results":{"default":null,"description":"Structured search results returned out-of-band by standalone web search.\n\nThese stay as opaque JSON at the extension/app-server boundary so new result fields and result types can pass through without a Codex release.","items":true,"type":["array","null"]},"type":{"enum":["webSearch"],"title":"WebSearchThreadItemType","type":"string"}},"required":["id","query","type"],"title":"WebSearchThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"path":{"${'$'}ref":"#/definitions/LegacyAppPathString"},"type":{"enum":["imageView"],"title":"ImageViewThreadItemType","type":"string"}},"required":["id","path","type"],"title":"ImageViewThreadItem","type":"object"},{"description":"Display item emitted by the interruptible `clock.sleep` tool.","properties":{"durationMs":{"format":"uint64","minimum":0.0,"type":"integer"},"id":{"type":"string"},"type":{"enum":["sleep"],"title":"SleepThreadItemType","type":"string"}},"required":["durationMs","id","type"],"title":"SleepThreadItem","type":"object"},{"properties":{"failure":{"anyOf":[{"${'$'}ref":"#/definitions/ImageGenerationFailure"},{"type":"null"}],"default":null},"id":{"type":"string"},"result":{"type":"string"},"revisedPrompt":{"type":["string","null"]},"savedPath":{"anyOf":[{"${'$'}ref":"#/definitions/AbsolutePathBuf"},{"type":"null"}]},"status":{"type":"string"},"transparentBackground":{"default":null,"type":["boolean","null"]},"type":{"enum":["imageGeneration"],"title":"ImageGenerationThreadItemType","type":"string"}},"required":["id","result","status","type"],"title":"ImageGenerationThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"review":{"type":"string"},"type":{"enum":["enteredReviewMode"],"title":"EnteredReviewModeThreadItemType","type":"string"}},"required":["id","review","type"],"title":"EnteredReviewModeThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"review":{"type":"string"},"type":{"enum":["exitedReviewMode"],"title":"ExitedReviewModeThreadItemType","type":"string"}},"required":["id","review","type"],"title":"ExitedReviewModeThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"type":{"enum":["contextCompaction"],"title":"ContextCompactionThreadItemType","type":"string"}},"required":["id","type"],"title":"ContextCompactionThreadItem","type":"object"}]},"MessagePhase":{"description":"Classifies an assistant message as interim commentary or final answer text.\n\nProviders do not emit this consistently, so callers must treat `None` as \"phase unknown\" and keep compatibility behavior for legacy models.","oneOf":[{"description":"Mid-turn assistant text (for example preamble/progress narration).\n\nAdditional tool calls or assistant output may follow before turn completion.","enum":["commentary"],"type":"string"},{"description":"The assistant's terminal answer text for the current turn.","enum":["final_answer"],"type":"string"}]}}}""",
    "v2/ItemCompletedNotification.json" to """{"properties":{"completedAtMs":{"description":"Unix timestamp (in milliseconds) when this item lifecycle completed.","format":"int64","type":"integer"},"item":{"${'$'}ref":"#/definitions/ThreadItem"},"threadId":{"type":"string"},"turnId":{"type":"string"}},"required":["completedAtMs","item","threadId","turnId"],"type":"object","definitions":{"ThreadItem":{"oneOf":[{"properties":{"clientId":{"type":["string","null"]},"content":{"items":{"${'$'}ref":"#/definitions/UserInput"},"type":"array"},"id":{"type":"string"},"type":{"enum":["userMessage"],"title":"UserMessageThreadItemType","type":"string"}},"required":["content","id","type"],"title":"UserMessageThreadItem","type":"object"},{"properties":{"fragments":{"items":{"${'$'}ref":"#/definitions/HookPromptFragment"},"type":"array"},"id":{"type":"string"},"type":{"enum":["hookPrompt"],"title":"HookPromptThreadItemType","type":"string"}},"required":["fragments","id","type"],"title":"HookPromptThreadItem","type":"object"},{"properties":{"delivery":{"anyOf":[{"${'$'}ref":"#/definitions/AgentMessageDelivery"},{"type":"null"}],"default":null},"id":{"type":"string"},"memoryCitation":{"anyOf":[{"${'$'}ref":"#/definitions/MemoryCitation"},{"type":"null"}],"default":null},"phase":{"anyOf":[{"${'$'}ref":"#/definitions/MessagePhase"},{"type":"null"}],"default":null},"questions":{"default":null,"items":{"${'$'}ref":"#/definitions/AsyncUserInputQuestion"},"type":["array","null"]},"text":{"type":"string"},"type":{"enum":["agentMessage"],"title":"AgentMessageThreadItemType","type":"string"}},"required":["id","text","type"],"title":"AgentMessageThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"name":{"type":"string"},"namespace":{"type":["string","null"]},"output":{"${'$'}ref":"#/definitions/FunctionCallOutputBody"},"type":{"enum":["functionCallOutput"],"title":"FunctionCallOutputThreadItemType","type":"string"}},"required":["id","name","output","type"],"title":"FunctionCallOutputThreadItem","type":"object"},{"description":"EXPERIMENTAL - proposed plan item content. The completed plan item is authoritative and may not match the concatenation of `PlanDelta` text.","properties":{"id":{"type":"string"},"text":{"type":"string"},"type":{"enum":["plan"],"title":"PlanThreadItemType","type":"string"}},"required":["id","text","type"],"title":"PlanThreadItem","type":"object"},{"properties":{"content":{"default":[],"items":{"type":"string"},"type":"array"},"id":{"type":"string"},"summary":{"default":[],"items":{"type":"string"},"type":"array"},"type":{"enum":["reasoning"],"title":"ReasoningThreadItemType","type":"string"}},"required":["id","type"],"title":"ReasoningThreadItem","type":"object"},{"properties":{"aggregatedOutput":{"description":"The command's output, aggregated from stdout and stderr.","type":["string","null"]},"command":{"description":"The command to be executed.","type":"string"},"commandActions":{"description":"A best-effort parsing of the command to understand the action(s) it will perform. This returns a list of CommandAction objects because a single shell command may be composed of many commands piped together.","items":{"${'$'}ref":"#/definitions/CommandAction"},"type":"array"},"cwd":{"allOf":[{"${'$'}ref":"#/definitions/LegacyAppPathString"}],"description":"The command's working directory."},"durationMs":{"description":"The duration of the command execution in milliseconds.","format":"int64","type":["integer","null"]},"exitCode":{"description":"The command's exit code.","format":"int32","type":["integer","null"]},"id":{"type":"string"},"pluginId":{"default":null,"description":"Trusted first-party plugin id when this command resolves to one plugin script.","type":["string","null"]},"processId":{"description":"Identifier for the underlying PTY process (when available).","type":["string","null"]},"scriptPath":{"default":null,"description":"Safe plugin-relative path when this command resolves to one plugin script.","type":["string","null"]},"source":{"allOf":[{"${'$'}ref":"#/definitions/CommandExecutionSource"}],"default":"agent"},"status":{"${'$'}ref":"#/definitions/CommandExecutionStatus"},"type":{"enum":["commandExecution"],"title":"CommandExecutionThreadItemType","type":"string"}},"required":["command","commandActions","cwd","id","status","type"],"title":"CommandExecutionThreadItem","type":"object"},{"properties":{"changes":{"items":{"${'$'}ref":"#/definitions/FileUpdateChange"},"type":"array"},"id":{"type":"string"},"status":{"${'$'}ref":"#/definitions/PatchApplyStatus"},"type":{"enum":["fileChange"],"title":"FileChangeThreadItemType","type":"string"}},"required":["changes","id","status","type"],"title":"FileChangeThreadItem","type":"object"},{"properties":{"appContext":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallAppContext"},{"type":"null"}]},"arguments":true,"durationMs":{"description":"The duration of the MCP tool call in milliseconds.","format":"int64","type":["integer","null"]},"error":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallError"},{"type":"null"}]},"id":{"type":"string"},"mcpAppResourceUri":{"description":"Legacy compatibility field; prefer `mcpAppUi.resourceUri` when available.","type":["string","null"]},"mcpAppUi":{"anyOf":[{"${'$'}ref":"#/definitions/McpAppUi"},{"type":"null"}],"description":"Presentation captured from the invoked descriptor; absent in older history."},"pluginId":{"type":["string","null"]},"readOnlyHint":{"type":["boolean","null"]},"result":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallResult"},{"type":"null"}]},"server":{"type":"string"},"status":{"${'$'}ref":"#/definitions/McpToolCallStatus"},"tool":{"type":"string"},"type":{"enum":["mcpToolCall"],"title":"McpToolCallThreadItemType","type":"string"}},"required":["arguments","id","server","status","tool","type"],"title":"McpToolCallThreadItem","type":"object"},{"properties":{"arguments":true,"contentItems":{"items":{"${'$'}ref":"#/definitions/DynamicToolCallOutputContentItem"},"type":["array","null"]},"durationMs":{"description":"The duration of the dynamic tool call in milliseconds.","format":"int64","type":["integer","null"]},"id":{"type":"string"},"namespace":{"type":["string","null"]},"status":{"${'$'}ref":"#/definitions/DynamicToolCallStatus"},"success":{"type":["boolean","null"]},"tool":{"type":"string"},"type":{"enum":["dynamicToolCall"],"title":"DynamicToolCallThreadItemType","type":"string"}},"required":["arguments","id","status","tool","type"],"title":"DynamicToolCallThreadItem","type":"object"},{"properties":{"agentsStates":{"additionalProperties":{"${'$'}ref":"#/definitions/CollabAgentState"},"description":"Last known status of the target agents, when available.","type":"object"},"id":{"description":"Unique identifier for this collab tool call.","type":"string"},"model":{"description":"Model requested for the spawned agent, when applicable.","type":["string","null"]},"prompt":{"description":"Prompt text sent as part of the collab tool call, when available.","type":["string","null"]},"reasoningEffort":{"anyOf":[{"${'$'}ref":"#/definitions/ReasoningEffort"},{"type":"null"}],"description":"Reasoning effort requested for the spawned agent, when applicable."},"receiverThreadIds":{"description":"Thread ID of the receiving agent, when applicable. In case of spawn operation, this corresponds to the newly spawned agent.","items":{"type":"string"},"type":"array"},"senderThreadId":{"description":"Thread ID of the agent issuing the collab request.","type":"string"},"status":{"allOf":[{"${'$'}ref":"#/definitions/CollabAgentToolCallStatus"}],"description":"Current status of the collab tool call."},"tool":{"allOf":[{"${'$'}ref":"#/definitions/CollabAgentTool"}],"description":"Name of the collab tool that was invoked."},"type":{"enum":["collabAgentToolCall"],"title":"CollabAgentToolCallThreadItemType","type":"string"}},"required":["agentsStates","id","receiverThreadIds","senderThreadId","status","tool","type"],"title":"CollabAgentToolCallThreadItem","type":"object"},{"properties":{"agentPath":{"type":"string"},"agentThreadId":{"type":"string"},"id":{"type":"string"},"kind":{"${'$'}ref":"#/definitions/SubAgentActivityKind"},"type":{"enum":["subAgentActivity"],"title":"SubAgentActivityThreadItemType","type":"string"}},"required":["agentPath","agentThreadId","id","kind","type"],"title":"SubAgentActivityThreadItem","type":"object"},{"properties":{"action":{"anyOf":[{"${'$'}ref":"#/definitions/WebSearchAction"},{"type":"null"}]},"id":{"type":"string"},"query":{"type":"string"},"results":{"default":null,"description":"Structured search results returned out-of-band by standalone web search.\n\nThese stay as opaque JSON at the extension/app-server boundary so new result fields and result types can pass through without a Codex release.","items":true,"type":["array","null"]},"type":{"enum":["webSearch"],"title":"WebSearchThreadItemType","type":"string"}},"required":["id","query","type"],"title":"WebSearchThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"path":{"${'$'}ref":"#/definitions/LegacyAppPathString"},"type":{"enum":["imageView"],"title":"ImageViewThreadItemType","type":"string"}},"required":["id","path","type"],"title":"ImageViewThreadItem","type":"object"},{"description":"Display item emitted by the interruptible `clock.sleep` tool.","properties":{"durationMs":{"format":"uint64","minimum":0.0,"type":"integer"},"id":{"type":"string"},"type":{"enum":["sleep"],"title":"SleepThreadItemType","type":"string"}},"required":["durationMs","id","type"],"title":"SleepThreadItem","type":"object"},{"properties":{"failure":{"anyOf":[{"${'$'}ref":"#/definitions/ImageGenerationFailure"},{"type":"null"}],"default":null},"id":{"type":"string"},"result":{"type":"string"},"revisedPrompt":{"type":["string","null"]},"savedPath":{"anyOf":[{"${'$'}ref":"#/definitions/AbsolutePathBuf"},{"type":"null"}]},"status":{"type":"string"},"transparentBackground":{"default":null,"type":["boolean","null"]},"type":{"enum":["imageGeneration"],"title":"ImageGenerationThreadItemType","type":"string"}},"required":["id","result","status","type"],"title":"ImageGenerationThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"review":{"type":"string"},"type":{"enum":["enteredReviewMode"],"title":"EnteredReviewModeThreadItemType","type":"string"}},"required":["id","review","type"],"title":"EnteredReviewModeThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"review":{"type":"string"},"type":{"enum":["exitedReviewMode"],"title":"ExitedReviewModeThreadItemType","type":"string"}},"required":["id","review","type"],"title":"ExitedReviewModeThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"type":{"enum":["contextCompaction"],"title":"ContextCompactionThreadItemType","type":"string"}},"required":["id","type"],"title":"ContextCompactionThreadItem","type":"object"}]},"MessagePhase":{"description":"Classifies an assistant message as interim commentary or final answer text.\n\nProviders do not emit this consistently, so callers must treat `None` as \"phase unknown\" and keep compatibility behavior for legacy models.","oneOf":[{"description":"Mid-turn assistant text (for example preamble/progress narration).\n\nAdditional tool calls or assistant output may follow before turn completion.","enum":["commentary"],"type":"string"},{"description":"The assistant's terminal answer text for the current turn.","enum":["final_answer"],"type":"string"}]}}}""",
    "v2/TurnStartedNotification.json" to """{"properties":{"threadId":{"type":"string"},"turn":{"${'$'}ref":"#/definitions/Turn"}},"required":["threadId","turn"],"type":"object","definitions":{"Turn":{"properties":{"completedAt":{"description":"Unix timestamp (in seconds) when the turn completed.","format":"int64","type":["integer","null"]},"durationMs":{"description":"Duration between turn start and completion in milliseconds, if known.","format":"int64","type":["integer","null"]},"error":{"anyOf":[{"${'$'}ref":"#/definitions/TurnError"},{"type":"null"}],"description":"Error associated with a failed or interrupted turn."},"id":{"description":"Identifier for this turn. Codex-generated turn IDs are UUIDv7.","type":"string"},"items":{"description":"Thread items currently included in this turn payload.","items":{"${'$'}ref":"#/definitions/ThreadItem"},"type":"array"},"itemsView":{"allOf":[{"${'$'}ref":"#/definitions/TurnItemsView"}],"default":"full","description":"Describes how much of `items` has been loaded for this turn."},"startedAt":{"description":"Unix timestamp (in seconds) when the turn started.","format":"int64","type":["integer","null"]},"status":{"${'$'}ref":"#/definitions/TurnStatus"}},"required":["id","items","status"],"type":"object"},"TurnStatus":{"enum":["completed","interrupted","failed","inProgress"],"type":"string"}}}""",
    "v2/TurnCompletedNotification.json" to """{"properties":{"threadId":{"type":"string"},"turn":{"${'$'}ref":"#/definitions/Turn"}},"required":["threadId","turn"],"type":"object","definitions":{"Turn":{"properties":{"completedAt":{"description":"Unix timestamp (in seconds) when the turn completed.","format":"int64","type":["integer","null"]},"durationMs":{"description":"Duration between turn start and completion in milliseconds, if known.","format":"int64","type":["integer","null"]},"error":{"anyOf":[{"${'$'}ref":"#/definitions/TurnError"},{"type":"null"}],"description":"Error associated with a failed or interrupted turn."},"id":{"description":"Identifier for this turn. Codex-generated turn IDs are UUIDv7.","type":"string"},"items":{"description":"Thread items currently included in this turn payload.","items":{"${'$'}ref":"#/definitions/ThreadItem"},"type":"array"},"itemsView":{"allOf":[{"${'$'}ref":"#/definitions/TurnItemsView"}],"default":"full","description":"Describes how much of `items` has been loaded for this turn."},"startedAt":{"description":"Unix timestamp (in seconds) when the turn started.","format":"int64","type":["integer","null"]},"status":{"${'$'}ref":"#/definitions/TurnStatus"}},"required":["id","items","status"],"type":"object"},"TurnStatus":{"enum":["completed","interrupted","failed","inProgress"],"type":"string"},"ThreadItem":{"oneOf":[{"properties":{"clientId":{"type":["string","null"]},"content":{"items":{"${'$'}ref":"#/definitions/UserInput"},"type":"array"},"id":{"type":"string"},"type":{"enum":["userMessage"],"title":"UserMessageThreadItemType","type":"string"}},"required":["content","id","type"],"title":"UserMessageThreadItem","type":"object"},{"properties":{"fragments":{"items":{"${'$'}ref":"#/definitions/HookPromptFragment"},"type":"array"},"id":{"type":"string"},"type":{"enum":["hookPrompt"],"title":"HookPromptThreadItemType","type":"string"}},"required":["fragments","id","type"],"title":"HookPromptThreadItem","type":"object"},{"properties":{"delivery":{"anyOf":[{"${'$'}ref":"#/definitions/AgentMessageDelivery"},{"type":"null"}],"default":null},"id":{"type":"string"},"memoryCitation":{"anyOf":[{"${'$'}ref":"#/definitions/MemoryCitation"},{"type":"null"}],"default":null},"phase":{"anyOf":[{"${'$'}ref":"#/definitions/MessagePhase"},{"type":"null"}],"default":null},"questions":{"default":null,"items":{"${'$'}ref":"#/definitions/AsyncUserInputQuestion"},"type":["array","null"]},"text":{"type":"string"},"type":{"enum":["agentMessage"],"title":"AgentMessageThreadItemType","type":"string"}},"required":["id","text","type"],"title":"AgentMessageThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"name":{"type":"string"},"namespace":{"type":["string","null"]},"output":{"${'$'}ref":"#/definitions/FunctionCallOutputBody"},"type":{"enum":["functionCallOutput"],"title":"FunctionCallOutputThreadItemType","type":"string"}},"required":["id","name","output","type"],"title":"FunctionCallOutputThreadItem","type":"object"},{"description":"EXPERIMENTAL - proposed plan item content. The completed plan item is authoritative and may not match the concatenation of `PlanDelta` text.","properties":{"id":{"type":"string"},"text":{"type":"string"},"type":{"enum":["plan"],"title":"PlanThreadItemType","type":"string"}},"required":["id","text","type"],"title":"PlanThreadItem","type":"object"},{"properties":{"content":{"default":[],"items":{"type":"string"},"type":"array"},"id":{"type":"string"},"summary":{"default":[],"items":{"type":"string"},"type":"array"},"type":{"enum":["reasoning"],"title":"ReasoningThreadItemType","type":"string"}},"required":["id","type"],"title":"ReasoningThreadItem","type":"object"},{"properties":{"aggregatedOutput":{"description":"The command's output, aggregated from stdout and stderr.","type":["string","null"]},"command":{"description":"The command to be executed.","type":"string"},"commandActions":{"description":"A best-effort parsing of the command to understand the action(s) it will perform. This returns a list of CommandAction objects because a single shell command may be composed of many commands piped together.","items":{"${'$'}ref":"#/definitions/CommandAction"},"type":"array"},"cwd":{"allOf":[{"${'$'}ref":"#/definitions/LegacyAppPathString"}],"description":"The command's working directory."},"durationMs":{"description":"The duration of the command execution in milliseconds.","format":"int64","type":["integer","null"]},"exitCode":{"description":"The command's exit code.","format":"int32","type":["integer","null"]},"id":{"type":"string"},"pluginId":{"default":null,"description":"Trusted first-party plugin id when this command resolves to one plugin script.","type":["string","null"]},"processId":{"description":"Identifier for the underlying PTY process (when available).","type":["string","null"]},"scriptPath":{"default":null,"description":"Safe plugin-relative path when this command resolves to one plugin script.","type":["string","null"]},"source":{"allOf":[{"${'$'}ref":"#/definitions/CommandExecutionSource"}],"default":"agent"},"status":{"${'$'}ref":"#/definitions/CommandExecutionStatus"},"type":{"enum":["commandExecution"],"title":"CommandExecutionThreadItemType","type":"string"}},"required":["command","commandActions","cwd","id","status","type"],"title":"CommandExecutionThreadItem","type":"object"},{"properties":{"changes":{"items":{"${'$'}ref":"#/definitions/FileUpdateChange"},"type":"array"},"id":{"type":"string"},"status":{"${'$'}ref":"#/definitions/PatchApplyStatus"},"type":{"enum":["fileChange"],"title":"FileChangeThreadItemType","type":"string"}},"required":["changes","id","status","type"],"title":"FileChangeThreadItem","type":"object"},{"properties":{"appContext":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallAppContext"},{"type":"null"}]},"arguments":true,"durationMs":{"description":"The duration of the MCP tool call in milliseconds.","format":"int64","type":["integer","null"]},"error":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallError"},{"type":"null"}]},"id":{"type":"string"},"mcpAppResourceUri":{"description":"Legacy compatibility field; prefer `mcpAppUi.resourceUri` when available.","type":["string","null"]},"mcpAppUi":{"anyOf":[{"${'$'}ref":"#/definitions/McpAppUi"},{"type":"null"}],"description":"Presentation captured from the invoked descriptor; absent in older history."},"pluginId":{"type":["string","null"]},"readOnlyHint":{"type":["boolean","null"]},"result":{"anyOf":[{"${'$'}ref":"#/definitions/McpToolCallResult"},{"type":"null"}]},"server":{"type":"string"},"status":{"${'$'}ref":"#/definitions/McpToolCallStatus"},"tool":{"type":"string"},"type":{"enum":["mcpToolCall"],"title":"McpToolCallThreadItemType","type":"string"}},"required":["arguments","id","server","status","tool","type"],"title":"McpToolCallThreadItem","type":"object"},{"properties":{"arguments":true,"contentItems":{"items":{"${'$'}ref":"#/definitions/DynamicToolCallOutputContentItem"},"type":["array","null"]},"durationMs":{"description":"The duration of the dynamic tool call in milliseconds.","format":"int64","type":["integer","null"]},"id":{"type":"string"},"namespace":{"type":["string","null"]},"status":{"${'$'}ref":"#/definitions/DynamicToolCallStatus"},"success":{"type":["boolean","null"]},"tool":{"type":"string"},"type":{"enum":["dynamicToolCall"],"title":"DynamicToolCallThreadItemType","type":"string"}},"required":["arguments","id","status","tool","type"],"title":"DynamicToolCallThreadItem","type":"object"},{"properties":{"agentsStates":{"additionalProperties":{"${'$'}ref":"#/definitions/CollabAgentState"},"description":"Last known status of the target agents, when available.","type":"object"},"id":{"description":"Unique identifier for this collab tool call.","type":"string"},"model":{"description":"Model requested for the spawned agent, when applicable.","type":["string","null"]},"prompt":{"description":"Prompt text sent as part of the collab tool call, when available.","type":["string","null"]},"reasoningEffort":{"anyOf":[{"${'$'}ref":"#/definitions/ReasoningEffort"},{"type":"null"}],"description":"Reasoning effort requested for the spawned agent, when applicable."},"receiverThreadIds":{"description":"Thread ID of the receiving agent, when applicable. In case of spawn operation, this corresponds to the newly spawned agent.","items":{"type":"string"},"type":"array"},"senderThreadId":{"description":"Thread ID of the agent issuing the collab request.","type":"string"},"status":{"allOf":[{"${'$'}ref":"#/definitions/CollabAgentToolCallStatus"}],"description":"Current status of the collab tool call."},"tool":{"allOf":[{"${'$'}ref":"#/definitions/CollabAgentTool"}],"description":"Name of the collab tool that was invoked."},"type":{"enum":["collabAgentToolCall"],"title":"CollabAgentToolCallThreadItemType","type":"string"}},"required":["agentsStates","id","receiverThreadIds","senderThreadId","status","tool","type"],"title":"CollabAgentToolCallThreadItem","type":"object"},{"properties":{"agentPath":{"type":"string"},"agentThreadId":{"type":"string"},"id":{"type":"string"},"kind":{"${'$'}ref":"#/definitions/SubAgentActivityKind"},"type":{"enum":["subAgentActivity"],"title":"SubAgentActivityThreadItemType","type":"string"}},"required":["agentPath","agentThreadId","id","kind","type"],"title":"SubAgentActivityThreadItem","type":"object"},{"properties":{"action":{"anyOf":[{"${'$'}ref":"#/definitions/WebSearchAction"},{"type":"null"}]},"id":{"type":"string"},"query":{"type":"string"},"results":{"default":null,"description":"Structured search results returned out-of-band by standalone web search.\n\nThese stay as opaque JSON at the extension/app-server boundary so new result fields and result types can pass through without a Codex release.","items":true,"type":["array","null"]},"type":{"enum":["webSearch"],"title":"WebSearchThreadItemType","type":"string"}},"required":["id","query","type"],"title":"WebSearchThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"path":{"${'$'}ref":"#/definitions/LegacyAppPathString"},"type":{"enum":["imageView"],"title":"ImageViewThreadItemType","type":"string"}},"required":["id","path","type"],"title":"ImageViewThreadItem","type":"object"},{"description":"Display item emitted by the interruptible `clock.sleep` tool.","properties":{"durationMs":{"format":"uint64","minimum":0.0,"type":"integer"},"id":{"type":"string"},"type":{"enum":["sleep"],"title":"SleepThreadItemType","type":"string"}},"required":["durationMs","id","type"],"title":"SleepThreadItem","type":"object"},{"properties":{"failure":{"anyOf":[{"${'$'}ref":"#/definitions/ImageGenerationFailure"},{"type":"null"}],"default":null},"id":{"type":"string"},"result":{"type":"string"},"revisedPrompt":{"type":["string","null"]},"savedPath":{"anyOf":[{"${'$'}ref":"#/definitions/AbsolutePathBuf"},{"type":"null"}]},"status":{"type":"string"},"transparentBackground":{"default":null,"type":["boolean","null"]},"type":{"enum":["imageGeneration"],"title":"ImageGenerationThreadItemType","type":"string"}},"required":["id","result","status","type"],"title":"ImageGenerationThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"review":{"type":"string"},"type":{"enum":["enteredReviewMode"],"title":"EnteredReviewModeThreadItemType","type":"string"}},"required":["id","review","type"],"title":"EnteredReviewModeThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"review":{"type":"string"},"type":{"enum":["exitedReviewMode"],"title":"ExitedReviewModeThreadItemType","type":"string"}},"required":["id","review","type"],"title":"ExitedReviewModeThreadItem","type":"object"},{"properties":{"id":{"type":"string"},"type":{"enum":["contextCompaction"],"title":"ContextCompactionThreadItemType","type":"string"}},"required":["id","type"],"title":"ContextCompactionThreadItem","type":"object"}]}}}""",
    "v2/ThreadStartedNotification.json" to """{"properties":{"thread":{"${'$'}ref":"#/definitions/Thread"}},"required":["thread"],"type":"object","definitions":{"Thread":{"properties":{"agentNickname":{"description":"Optional random unique nickname assigned to an AgentControl-spawned sub-agent.","type":["string","null"]},"agentRole":{"description":"Optional role (agent_role) assigned to an AgentControl-spawned sub-agent.","type":["string","null"]},"canAcceptDirectInput":{"description":"Whether the app server accepts direct turn input for this loaded thread. `None` means the capability is unavailable, such as for an unloaded stored thread.","type":["boolean","null"]},"cliVersion":{"description":"Version of the CLI that created the thread.","type":"string"},"createdAt":{"description":"Unix timestamp (in seconds) when the thread was created.","format":"int64","type":"integer"},"cwd":{"allOf":[{"${'$'}ref":"#/definitions/AbsolutePathBuf"}],"description":"Working directory captured for the thread."},"daybreakEnabled":{"description":"Saved Daybreak choice, independent of turn execution. Null if unset.","type":["boolean","null"]},"environments":{"default":null,"description":"Current environments for a loaded thread, in priority order, primary first. `null` means the thread is not loaded or the server does not expose its selection. An empty list means no environments are selected. This does not report connection status.","items":{"${'$'}ref":"#/definitions/ThreadEnvironment"},"type":["array","null"]},"ephemeral":{"description":"Whether the thread is ephemeral and should not be materialized on disk.","type":"boolean"},"extra":{"anyOf":[{"${'$'}ref":"#/definitions/ThreadExtra"},{"type":"null"}],"description":"Optional implementation-specific thread data."},"forkedFromId":{"description":"Source thread id when this thread was created by forking another thread.","type":["string","null"]},"gitInfo":{"anyOf":[{"${'$'}ref":"#/definitions/GitInfo"},{"type":"null"}],"description":"Optional Git metadata captured when the thread was created."},"historyMode":{"allOf":[{"${'$'}ref":"#/definitions/ThreadHistoryMode"}],"default":"legacy","description":"Persisted thread history contract selected when this thread was created."},"id":{"description":"Identifier for this thread. Codex-generated thread IDs are UUIDv7.","type":"string"},"model":{"description":"Current configured model when loaded, otherwise the latest persisted model. Null when unavailable. This is not per-turn execution telemetry.","type":["string","null"]},"modelProvider":{"description":"Model provider used for this thread (for example, 'openai').","type":"string"},"name":{"description":"Optional user-facing thread title.","type":["string","null"]},"originator":{"description":"Originator recorded when the thread was created, independent of its current client or executor. Null when the recorded originator is unavailable.","type":["string","null"]},"parentThreadId":{"description":"The ID of the parent thread. This will only be set if this thread is a subagent.","type":["string","null"]},"path":{"description":"[UNSTABLE] Path to the thread on disk.","type":["string","null"]},"preview":{"description":"Usually the first user message in the thread, if available.","type":"string"},"projectId":{"description":"Canonical project assignment owned by app-server, if any.","type":["string","null"]},"reasoningEffort":{"anyOf":[{"${'$'}ref":"#/definitions/ReasoningEffort"},{"type":"null"}],"description":"Current configured reasoning effort when loaded, otherwise the latest persisted effort. Null when unset or unavailable. This is not per-turn execution telemetry."},"recencyAt":{"description":"Unix timestamp (in seconds) used for thread recency ordering.","format":"int64","type":["integer","null"]},"section":{"anyOf":[{"${'$'}ref":"#/definitions/ThreadSection"},{"type":"null"}],"default":null,"description":"The independently persisted section selected for this thread, if any."},"sectionEnteredAt":{"default":null,"description":"Unix timestamp in seconds when the thread entered its current section.","format":"int64","type":["integer","null"]},"sessionId":{"description":"Session id shared by threads that belong to the same session tree.","type":"string"},"source":{"allOf":[{"${'$'}ref":"#/definitions/SessionSource"}],"description":"Origin of the thread (CLI, VSCode, codex exec, codex app-server, etc.)."},"status":{"allOf":[{"${'$'}ref":"#/definitions/ThreadStatus"}],"description":"Current runtime status for the thread."},"threadSource":{"anyOf":[{"${'$'}ref":"#/definitions/ThreadSource"},{"type":"null"}],"description":"Optional analytics source classification for this thread."},"turns":{"description":"Only populated on `thread/resume`, `thread/fork`, and `thread/read` (when `includeTurns` is true) responses. For all other responses and notifications returning a Thread, the turns field will be an empty list.","items":{"${'$'}ref":"#/definitions/Turn"},"type":"array"},"updatedAt":{"description":"Unix timestamp (in seconds) when the thread was last updated.","format":"int64","type":"integer"}},"required":["cliVersion","createdAt","cwd","ephemeral","id","modelProvider","preview","projectId","sessionId","source","status","turns","updatedAt"],"type":"object"}}}""",
    "v2/ThreadUnsubscribeResponse.json" to """{"properties":{"status":{"${'$'}ref":"#/definitions/ThreadUnsubscribeStatus"}},"required":["status"],"type":"object","definitions":{"ThreadUnsubscribeStatus":{"enum":["notLoaded","notSubscribed","unsubscribed"],"type":"string"}}}""",
    "v2/ThreadStatusChangedNotification.json" to """{"properties":{"status":{"${'$'}ref":"#/definitions/ThreadStatus"},"threadId":{"type":"string"}},"required":["status","threadId"],"type":"object","definitions":{"ThreadStatus":{"oneOf":[{"properties":{"type":{"enum":["notLoaded"],"title":"NotLoadedThreadStatusType","type":"string"}},"required":["type"],"title":"NotLoadedThreadStatus","type":"object"},{"properties":{"type":{"enum":["idle"],"title":"IdleThreadStatusType","type":"string"}},"required":["type"],"title":"IdleThreadStatus","type":"object"},{"properties":{"type":{"enum":["systemError"],"title":"SystemErrorThreadStatusType","type":"string"}},"required":["type"],"title":"SystemErrorThreadStatus","type":"object"},{"properties":{"activeFlags":{"items":{"${'$'}ref":"#/definitions/ThreadActiveFlag"},"type":"array"},"type":{"enum":["active"],"title":"ActiveThreadStatusType","type":"string"}},"required":["activeFlags","type"],"title":"ActiveThreadStatus","type":"object"}]},"ThreadActiveFlag":{"enum":["waitingOnApproval","waitingOnUserInput"],"type":"string"}}}""",
  )
}

/** Independent spec of the connection-level notifications Verity must opt out of. */
internal val OPTED_OUT_NOTIFICATIONS = listOf("configWarning", "remoteControl/status/changed", "account/updated", "account/rateLimits/updated")

/** Independent spec of what the child must never inherit: API keys and origin overrides. */
internal val REMOVED_ENVIRONMENT = setOf("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL", "CODEX_APP_SERVER_CHATGPT_BASE_URL", "CODEX_REFRESH_TOKEN_URL_OVERRIDE", "CODEX_REVOKE_TOKEN_URL_OVERRIDE")

internal class FakeCodexLauncher(val scenario: String = "success", private val failLaunch: Int? = null) {
  data class Child(val process: Process, val directory: Path, val command: List<String>, val environment: Map<String, String>)
  val children = CopyOnWriteArrayList<Child>()
  val directories = CopyOnWriteArraySet<Path>()
  var failCleanup = false
  private var launches = 0
  private val receipts = CopyOnWriteArrayList<Path>()
  private val releases = CopyOnWriteArrayList<Path>()
  data class WriteEntry(val pid: Long, val bytes: Int)
  val largeWriteEntered = kotlinx.coroutines.CompletableDeferred<WriteEntry>()
  val largeWriteFinished = kotlinx.coroutines.CompletableDeferred<Unit>()

  fun launch(command: List<String>, directory: Path, environment: Map<String, String>): Process {
    launches++
    directories.add(directory)
    if ("--out" in command) directories.add(Path.of(command[command.indexOf("--out") + 1]))
    if (launches == failLaunch) throw java.io.IOException("secret-launch-cause")
    val sources = listOf(FakeCodexAppServer::class.java, CodexIsolation::class.java, Json::class.java, JsonElement::class.java, me.chrisbanes.verity.agent.ModelBackendFailure::class.java, kotlinx.serialization.SerializationException::class.java, kotlin.Unit::class.java, kotlinx.coroutines.CoroutineScope::class.java)
    val classpath = sources.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
    val receipt = if (scenario.startsWith("model-")) Files.createTempFile("verity-fake-codex-", ".jsonl").also { receipts.add(it) } else null
    val release = if (scenario.startsWith("model-callback-") || scenario == "model-idle-callback") Files.createTempFile("verity-fake-release-", ".txt").also { releases.add(it) } else null
    // Codex always writes UTF-8. With the cleared environment, Linux would otherwise give the fake an ASCII stdout.
    val process = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString()) + listOfNotNull(receipt?.let { "-Dverity.fake.receipt=$it" }, release?.let { "-Dverity.fake.release=$it" }) + listOf("-Dstdout.encoding=UTF-8", "-cp", classpath, FakeCodexAppServer::class.java.name, scenario) + command.drop(1)).directory(directory.toFile()).apply {
      environment().clear()
      environment().putAll(environment)
    }.start()
    val owned = if (scenario == "model-callback-blocked") ObservedProcess(process, largeWriteEntered, largeWriteFinished) else process
    val cleanupObserved = CleanupObservedProcess(owned) { failCleanup }
    children.add(Child(cleanupObserved, directory, command, environment))
    return cleanupObserved
  }

  /** Fail after the real exact child has exited, without leaving a live process. */
  private class CleanupObservedProcess(private val child: Process, private val fail: () -> Boolean) : Process() {
    override fun getOutputStream() = child.outputStream
    override fun getInputStream() = child.inputStream
    override fun getErrorStream() = child.errorStream
    override fun waitFor() = child.waitFor()
    override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean {
      val exited = child.waitFor(timeout, unit)
      if (exited && fail()) throw java.io.IOException("secret-cleanup-cause")
      return exited
    }
    override fun exitValue() = child.exitValue()
    override fun destroy() = child.destroy()
    override fun destroyForcibly(): Process {
      child.destroyForcibly()
      return this
    }
    override fun isAlive() = child.isAlive
    override fun pid() = child.pid()
    override fun toHandle() = child.toHandle()
  }

  /** The wrapper observes entry to the real child's pipe; every process operation
   * delegates to the same exact handle. No payload is copied or retained. */
  private class ObservedProcess(
    private val child: Process,
    private val entered: kotlinx.coroutines.CompletableDeferred<WriteEntry>,
    private val finished: kotlinx.coroutines.CompletableDeferred<Unit>,
  ) : Process() {
    private val output = object : java.io.FilterOutputStream(child.outputStream) {
      override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val observed = length >= 3 * 1024 * 1024
        if (observed) entered.complete(WriteEntry(child.pid(), length))
        try {
          out.write(bytes, offset, length)
        } finally {
          if (observed) finished.complete(Unit)
        }
      }
      override fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
    }
    override fun getOutputStream() = output
    override fun getInputStream() = child.inputStream
    override fun getErrorStream() = child.errorStream
    override fun waitFor() = child.waitFor()
    override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit) = child.waitFor(timeout, unit)
    override fun exitValue() = child.exitValue()
    override fun destroy() = child.destroy()
    override fun destroyForcibly(): Process {
      child.destroyForcibly()
      return this
    }
    override fun isAlive() = child.isAlive
    override fun pid() = child.pid()
    override fun toHandle() = child.toHandle()
  }

  suspend fun prepare(startupMillis: Long = 30_000): CodexModelBackend = CodexModelBackend.prepare(
    executable = { Path.of("injected-jvm-fake") },
    launch = ::launch,
    host = "Mac OS X",
    environment = REMOVED_ENVIRONMENT.associateWith { "fake-unsafe-value" } + mapOf("CODEX_HOME" to "/unused-codex-owned-home", "CODEX_SQLITE_HOME" to "/unused-codex-owned-database"),
    startupMillis = startupMillis,
  )

  suspend fun capturedFrames(): List<JsonObject> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    receipts.flatMap { path ->
      if (Files.size(path) > 1024 * 1024) error("fixture receipt bound")
      val source = Files.readString(path)
      val complete = source.substringBeforeLast('\n', "")
      if (complete.isEmpty()) emptyList() else complete.split('\n').also { if (it.size > 64) error("fixture frame bound") }.map { Json.parseToJsonElement(it).jsonObject }
    }
  }

  suspend fun releaseCallbacks() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { releases.forEach { Files.writeString(it, "release") } }

  suspend fun cleanupReceipts() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
    receipts.forEach { Files.deleteIfExists(it) }
    releases.forEach { Files.deleteIfExists(it) }
  }

  suspend fun verifyCleanup() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    children.forEach { child -> assertk.assertThat(child.process.isAlive, "scenario=$scenario ownedPid=${child.process.pid()} childExited").isEqualTo(false) }
    directories.forEach { directory -> assertk.assertThat(Files.exists(directory), "scenario=$scenario ownedDirectoryAbsent").isEqualTo(false) }
    val evidence = buildJsonObject {
      put("scenario", scenario)
      put(
        "children",
        JsonArray(
          children.map { child ->
            buildJsonObject {
              put("pid", child.process.pid())
              put("directory", child.directory.toString())
              put("exited", !child.process.isAlive)
              put("directoryAbsent", !Files.exists(child.directory))
            }
          },
        ),
      )
      put("ownedDirectories", JsonArray(directories.map { JsonPrimitive(it.toString()) }))
      put("allOwnedDirectoriesAbsent", directories.none { Files.exists(it) })
    }
    recordCodexTestEvidence(evidence)
    receipts.forEach { path ->
      val source = Files.readString(path)
      if (source.isNotEmpty() && !source.endsWith('\n')) error("incomplete terminal fixture receipt")
    }
    receipts.forEach { Files.deleteIfExists(it) }
  }
}

/** Safe owned process/directory receipts are local build artifacts; an external path is opt-in. */
internal suspend fun recordCodexTestEvidence(evidence: JsonObject) {
  val path = System.getenv("VERITY_CODEX_TEST_EVIDENCE")?.let(Path::of) ?: Path.of("build", "codex-test-cleanup.jsonl")
  kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    Files.createDirectories(path.toAbsolutePath().parent)
    Files.writeString(path, evidence.toString() + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
  }
}

/** The TOML value subset Verity emits: basic strings, booleans, integers, arrays and inline tables. */
private class TomlSubset(private val source: String) {
  private var index = 0

  fun parse(): JsonElement? = runCatching {
    value().also {
      skipSpace()
      require(index == source.length)
    }
  }.getOrNull()

  private fun value(): JsonElement {
    skipSpace()
    return when (val char = source[index]) {
      '"' -> JsonPrimitive(string())

      '[' -> items(']') { value() }.let(::JsonArray)

      '{' -> JsonObject(items('}') { key() to expectAssignment() }.toMap())

      else -> {
        val token = source.substring(index).takeWhile { it.isLetterOrDigit() || it == '-' || it == '+' || it == '_' }
        require(token.isNotEmpty()) { "unexpected $char" }
        index += token.length
        when {
          token == "true" -> JsonPrimitive(true)
          token == "false" -> JsonPrimitive(false)
          else -> JsonPrimitive(token.toLong())
        }
      }
    }
  }

  private fun expectAssignment(): JsonElement {
    skipSpace()
    require(source[index++] == '=')
    return value()
  }

  private fun key(): String {
    skipSpace()
    if (source[index] == '"') return string()
    val bare = source.substring(index).takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
    require(bare.isNotEmpty())
    index += bare.length
    return bare
  }

  private fun <T> items(close: Char, item: () -> T): List<T> {
    index++
    val result = mutableListOf<T>()
    skipSpace()
    if (source[index] == close) return result.also { index++ }
    while (true) {
      result += item()
      skipSpace()
      when (source[index++]) {
        ',' -> Unit
        close -> return result
        else -> error("unterminated")
      }
    }
  }

  private fun string(): String {
    index++
    val result = StringBuilder()
    while (true) {
      val char = source[index++]
      require(char == '\t' || (char >= ' ' && char != '\u007f')) { "control character" }
      when (char) {
        '"' -> return result.toString()

        '\\' -> when (val escape = source[index++]) {
          'b' -> result.append('\b')
          't' -> result.append('\t')
          'n' -> result.append('\n')
          'f' -> result.append('\u000c')
          'r' -> result.append('\r')
          '"', '\\' -> result.append(escape)
          'u' -> result.appendCodePoint(source.substring(index, index + 4).toInt(16)).also { index += 4 }
          'U' -> result.appendCodePoint(source.substring(index, index + 8).toInt(16)).also { index += 8 }
          else -> error("escape")
        }

        else -> result.append(char)
      }
    }
  }

  private fun skipSpace() {
    while (index < source.length && (source[index] == ' ' || source[index] == '\t')) index++
  }
}
