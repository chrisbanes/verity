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
        if (name.endsWith("InitializeParams.json") && scenario == "missing-gateway") {
          val definitions = schema.getValue("definitions").jsonObject
          val capabilities = definitions.getValue("InitializeCapabilities").jsonObject
          val properties = capabilities.getValue("properties").jsonObject - "explicitGatewayOauth"
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
    val config = mutableMapOf<String, JsonElement>()
    command.windowed(2).filter { it[0] == "-c" }.forEach { pair ->
      val (key, value) = pair[1].split('=', limit = 2)
      insert(config, keys(key), Json.parseToJsonElement(value))
    }
    val final = command.any { it.startsWith("mcp_servers.") }
    val names = listOf("punctuation.a\"b\\c", "space and [brackets]")
    CodexIsolation.groups.forEach { group ->
      val existing = (config[group] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
      names.forEach { name -> if (name !in existing) existing[name] = buildJsonObject { put("enabled", true) } }
      if (scenario == "drift" && final) existing["new-name"] = buildJsonObject { put("enabled", true) }
      config[group] = JsonObject(existing)
    }
    if (scenario == "denied-policy") config["sandbox_mode"] = JsonPrimitive("workspace-write")
    val emptyCwd = Files.list(Path.of(".")).use { !it.findAny().isPresent }
    if (!emptyCwd || System.getenv("OPENAI_API_KEY") != null || System.getenv("CODEX_API_KEY") != null) error("unsafe child launch")
    var initialized = false
    var notified = false
    var read = false
    generateSequence { readlnOrNull() }.forEach { line ->
      val frame = Json.parseToJsonElement(line).jsonObject
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
          read = true
          buildJsonObject {
            put("config", JsonObject(config))
            put(
              "observations",
              buildJsonObject {
                put("pid", ProcessHandle.current().pid())
                put("emptyCwd", emptyCwd)
                put("apiKeysAbsent", System.getenv("OPENAI_API_KEY") == null && System.getenv("CODEX_API_KEY") == null)
              },
            )
          }
        }

        else -> error("Unexpected RPC: $method")
      }
      println(
        buildJsonObject {
          put("id", frame.getValue("id"))
          put("result", result)
        },
      )
      if (scenario == "no-stdin" && final && method == "config/read") Thread.sleep(60_000)
    }
    if (!read && scenario !in setOf("timeout", "final-timeout", "callback")) error("No config read")
  }

  private fun keys(key: String): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var quoted = false
    var escaped = false
    key.forEachIndexed { index, char ->
      if (escaped) {
        escaped = false
      } else if (char == '\\' && quoted) {
        escaped = true
      } else if (char == '"') {
        quoted = !quoted
      } else if (char == '.' && !quoted) {
        result.add(key.substring(start, index))
        start = index + 1
      }
    }
    result.add(key.substring(start))
    return result.map { if (it.startsWith('"')) Json.parseToJsonElement(it).jsonPrimitive.content else it }
  }

  private fun insert(target: MutableMap<String, JsonElement>, keys: List<String>, value: JsonElement) {
    if (keys.size == 1) {
      target[keys[0]] = value
    } else {
      val child = (target[keys[0]] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
      insert(child, keys.drop(1), value)
      target[keys[0]] = JsonObject(child)
    }
  }

  // Reduced, unchanged schema fragments from the retained real 0.159.0 experimental schemas.
  private val schemaFixtures: Map<String, String> = mapOf(
    "v1/InitializeParams.json" to """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","definitions":{"ClientInfo":{"properties":{"name":{"type":"string"},"title":{"type":["string","null"]},"version":{"type":"string"}},"required":["name","version"],"type":"object"},"InitializeCapabilities":{"description":"Client-declared capabilities negotiated during initialize.","properties":{"experimentalApi":{"default":false,"description":"Opt into receiving experimental API methods and fields.","type":"boolean"},"explicitGatewayOauth":{"description":"Use explicit gateway OAuth login instead of automatic browser authorization. Applies to this app-server's gateway runtime; later connections cannot undo it.","type":"boolean"},"extensions":{"additionalProperties":true,"description":"MCP extension settings declared by the app-server client.","type":["object","null"]},"mcpServerOpenaiFormElicitation":{"description":"Legacy opt-in for the `openai/form` MCP extension.\n\nNew clients should declare `openai/form` in [`Self::extensions`].","type":"boolean"},"optOutNotificationMethods":{"description":"Exact notification method names that should be suppressed for this connection (for example `thread/started`).","items":{"type":"string"},"type":["array","null"]},"requestAttestation":{"default":false,"description":"Opt into `attestation/generate` requests for upstream `x-oai-attestation`.","type":"boolean"}},"type":"object"}},"properties":{"capabilities":{"anyOf":[{"${'$'}ref":"#/definitions/InitializeCapabilities"},{"type":"null"}]},"clientInfo":{"${'$'}ref":"#/definitions/ClientInfo"}},"required":["clientInfo"],"title":"InitializeParams","type":"object"}""",
    "v2/ThreadStartParams.json" to """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","definitions":{"AbsolutePathBuf":{"description":"A path that is guaranteed to be absolute and normalized (though it is not guaranteed to be canonicalized or exist on the filesystem).\n\nIMPORTANT: When deserializing an `AbsolutePathBuf`, a base path must be set using [AbsolutePathBufGuard::new]. If no base path is set, the deserialization will fail unless the path being deserialized is already absolute.","type":"string"},"DynamicToolSpec":{"oneOf":[{"properties":{"deferLoading":{"type":"boolean"},"description":{"type":"string"},"inputSchema":true,"name":{"type":"string"},"type":{"enum":["function"],"title":"FunctionDynamicToolSpecType","type":"string"}},"required":["description","inputSchema","name","type"],"title":"FunctionDynamicToolSpec","type":"object"},{"properties":{"description":{"type":"string"},"name":{"type":"string"},"tools":{"items":{"${'$'}ref":"#/definitions/DynamicToolNamespaceTool"},"type":"array"},"type":{"enum":["namespace"],"title":"NamespaceDynamicToolSpecType","type":"string"}},"required":["description","name","tools","type"],"title":"NamespaceDynamicToolSpec","type":"object"}]},"SelectedCapabilityRoot":{"description":"A user-selected root that can expose one or more runtime capabilities.","properties":{"id":{"description":"Stable identifier supplied by the capability selection platform.","type":"string"},"location":{"allOf":[{"${'$'}ref":"#/definitions/CapabilityRootLocation"}],"description":"Where the selected root can be resolved."}},"required":["id","location"],"type":"object"},"TurnEnvironmentParams":{"properties":{"cwd":{"${'$'}ref":"#/definitions/LegacyAppPathString"},"environmentId":{"type":"string"},"runtimeWorkspaceRoots":{"description":"Environment-native runtime workspace roots. Omitted defaults to `cwd`.","items":{"${'$'}ref":"#/definitions/LegacyAppPathString"},"type":["array","null"]}},"required":["cwd","environmentId"],"type":"object"}},"properties":{"allowProviderModelFallback":{"description":"Allow a provider with an authoritative static model catalog to replace an unavailable requested model with its default.","type":"boolean"},"dynamicTools":{"default":null,"items":{"${'$'}ref":"#/definitions/DynamicToolSpec"},"type":["array","null"]},"environments":{"description":"Optional sticky environments for this thread.\n\nOmitted selects the default environment when environment access is enabled. Empty disables environment access for turns that do not provide a turn override. Non-empty selects the first environment as the current turn environment.","items":{"${'$'}ref":"#/definitions/TurnEnvironmentParams"},"type":["array","null"]},"ephemeral":{"type":["boolean","null"]},"experimentalRawEvents":{"description":"If true, opt into emitting raw Responses API items on the event stream. This is for internal use only (e.g. Codex Cloud).","type":"boolean"},"runtimeWorkspaceRoots":{"description":"Replace the thread's runtime workspace roots. Paths must be absolute.","items":{"${'$'}ref":"#/definitions/AbsolutePathBuf"},"type":["array","null"]},"selectedCapabilityRoots":{"description":"Capability roots selected for this thread by the hosting platform.","items":{"${'$'}ref":"#/definitions/SelectedCapabilityRoot"},"type":["array","null"]}},"title":"ThreadStartParams","type":"object"}""",
    "v2/ThreadStartResponse.json" to """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","definitions":{"AbsolutePathBuf":{"description":"A path that is guaranteed to be absolute and normalized (though it is not guaranteed to be canonicalized or exist on the filesystem).\n\nIMPORTANT: When deserializing an `AbsolutePathBuf`, a base path must be set using [AbsolutePathBufGuard::new]. If no base path is set, the deserialization will fail unless the path being deserialized is already absolute.","type":"string"},"AskForApproval":{"oneOf":[{"enum":["untrusted","on-request","never"],"type":"string"},{"additionalProperties":false,"properties":{"granular":{"properties":{"mcp_elicitations":{"type":"boolean"},"request_permissions":{"default":false,"type":"boolean"},"rules":{"type":"boolean"},"sandbox_approval":{"type":"boolean"},"skill_approval":{"default":false,"type":"boolean"}},"required":["mcp_elicitations","rules","sandbox_approval"],"type":"object"}},"required":["granular"],"title":"GranularAskForApproval","type":"object"}]},"SandboxPolicy":{"oneOf":[{"properties":{"type":{"enum":["dangerFullAccess"],"title":"DangerFullAccessSandboxPolicyType","type":"string"}},"required":["type"],"title":"DangerFullAccessSandboxPolicy","type":"object"},{"properties":{"networkAccess":{"default":false,"type":"boolean"},"type":{"enum":["readOnly"],"title":"ReadOnlySandboxPolicyType","type":"string"}},"required":["type"],"title":"ReadOnlySandboxPolicy","type":"object"},{"properties":{"networkAccess":{"allOf":[{"${'$'}ref":"#/definitions/NetworkAccess"}],"default":"restricted"},"type":{"enum":["externalSandbox"],"title":"ExternalSandboxSandboxPolicyType","type":"string"}},"required":["type"],"title":"ExternalSandboxSandboxPolicy","type":"object"},{"properties":{"excludeSlashTmp":{"default":false,"type":"boolean"},"excludeTmpdirEnvVar":{"default":false,"type":"boolean"},"networkAccess":{"default":false,"type":"boolean"},"type":{"enum":["workspaceWrite"],"title":"WorkspaceWriteSandboxPolicyType","type":"string"},"writableRoots":{"default":[],"items":{"${'$'}ref":"#/definitions/AbsolutePathBuf"},"type":"array"}},"required":["type"],"title":"WorkspaceWriteSandboxPolicy","type":"object"}]},"Thread":{"properties":{"agentNickname":{"description":"Optional random unique nickname assigned to an AgentControl-spawned sub-agent.","type":["string","null"]},"agentRole":{"description":"Optional role (agent_role) assigned to an AgentControl-spawned sub-agent.","type":["string","null"]},"canAcceptDirectInput":{"description":"Whether the app server accepts direct turn input for this loaded thread. `None` means the capability is unavailable, such as for an unloaded stored thread.","type":["boolean","null"]},"cliVersion":{"description":"Version of the CLI that created the thread.","type":"string"},"createdAt":{"description":"Unix timestamp (in seconds) when the thread was created.","format":"int64","type":"integer"},"cwd":{"allOf":[{"${'$'}ref":"#/definitions/AbsolutePathBuf"}],"description":"Working directory captured for the thread."},"daybreakEnabled":{"description":"Saved Daybreak choice, independent of turn execution. Null if unset.","type":["boolean","null"]},"environments":{"default":null,"description":"Current environments for a loaded thread, in priority order, primary first. `null` means the thread is not loaded or the server does not expose its selection. An empty list means no environments are selected. This does not report connection status.","items":{"${'$'}ref":"#/definitions/ThreadEnvironment"},"type":["array","null"]},"ephemeral":{"description":"Whether the thread is ephemeral and should not be materialized on disk.","type":"boolean"},"extra":{"anyOf":[{"${'$'}ref":"#/definitions/ThreadExtra"},{"type":"null"}],"description":"Optional implementation-specific thread data."},"forkedFromId":{"description":"Source thread id when this thread was created by forking another thread.","type":["string","null"]},"gitInfo":{"anyOf":[{"${'$'}ref":"#/definitions/GitInfo"},{"type":"null"}],"description":"Optional Git metadata captured when the thread was created."},"historyMode":{"allOf":[{"${'$'}ref":"#/definitions/ThreadHistoryMode"}],"default":"legacy","description":"Persisted thread history contract selected when this thread was created."},"id":{"description":"Identifier for this thread. Codex-generated thread IDs are UUIDv7.","type":"string"},"model":{"description":"Current configured model when loaded, otherwise the latest persisted model. Null when unavailable. This is not per-turn execution telemetry.","type":["string","null"]},"modelProvider":{"description":"Model provider used for this thread (for example, 'openai').","type":"string"},"name":{"description":"Optional user-facing thread title.","type":["string","null"]},"originator":{"description":"Originator recorded when the thread was created, independent of its current client or executor. Null when the recorded originator is unavailable.","type":["string","null"]},"parentThreadId":{"description":"The ID of the parent thread. This will only be set if this thread is a subagent.","type":["string","null"]},"path":{"description":"[UNSTABLE] Path to the thread on disk.","type":["string","null"]},"preview":{"description":"Usually the first user message in the thread, if available.","type":"string"},"projectId":{"description":"Canonical project assignment owned by app-server, if any.","type":["string","null"]},"reasoningEffort":{"anyOf":[{"${'$'}ref":"#/definitions/ReasoningEffort"},{"type":"null"}],"description":"Current configured reasoning effort when loaded, otherwise the latest persisted effort. Null when unset or unavailable. This is not per-turn execution telemetry."},"recencyAt":{"description":"Unix timestamp (in seconds) used for thread recency ordering.","format":"int64","type":["integer","null"]},"section":{"anyOf":[{"${'$'}ref":"#/definitions/ThreadSection"},{"type":"null"}],"default":null,"description":"The independently persisted section selected for this thread, if any."},"sectionEnteredAt":{"default":null,"description":"Unix timestamp in seconds when the thread entered its current section.","format":"int64","type":["integer","null"]},"sessionId":{"description":"Session id shared by threads that belong to the same session tree.","type":"string"},"source":{"allOf":[{"${'$'}ref":"#/definitions/SessionSource"}],"description":"Origin of the thread (CLI, VSCode, codex exec, codex app-server, etc.)."},"status":{"allOf":[{"${'$'}ref":"#/definitions/ThreadStatus"}],"description":"Current runtime status for the thread."},"threadSource":{"anyOf":[{"${'$'}ref":"#/definitions/ThreadSource"},{"type":"null"}],"description":"Optional analytics source classification for this thread."},"turns":{"description":"Only populated on `thread/resume`, `thread/fork`, and `thread/read` (when `includeTurns` is true) responses. For all other responses and notifications returning a Thread, the turns field will be an empty list.","items":{"${'$'}ref":"#/definitions/Turn"},"type":"array"},"updatedAt":{"description":"Unix timestamp (in seconds) when the thread was last updated.","format":"int64","type":"integer"}},"required":["cliVersion","createdAt","cwd","ephemeral","id","modelProvider","preview","projectId","sessionId","source","status","turns","updatedAt"],"type":"object"}},"properties":{"approvalPolicy":{"${'$'}ref":"#/definitions/AskForApproval"},"cwd":{"${'$'}ref":"#/definitions/AbsolutePathBuf"},"model":{"type":"string"},"modelProvider":{"type":"string"},"runtimeWorkspaceRoots":{"default":[],"description":"Thread-scoped runtime workspace roots used to materialize `:workspace_roots`.","items":{"${'$'}ref":"#/definitions/AbsolutePathBuf"},"type":"array"},"sandbox":{"allOf":[{"${'$'}ref":"#/definitions/SandboxPolicy"}],"description":"Legacy sandbox policy retained for compatibility. Experimental clients should prefer `activePermissionProfile` for profile provenance."},"thread":{"${'$'}ref":"#/definitions/Thread"}},"required":["approvalPolicy","approvalsReviewer","cwd","model","modelProvider","sandbox","thread"],"title":"ThreadStartResponse","type":"object"}""",
    "v2/RawResponseItemCompletedNotification.json" to """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","definitions":{"AgentMessageInputContent":{"oneOf":[{"properties":{"text":{"type":"string"},"type":{"enum":["input_text"],"title":"InputTextAgentMessageInputContentType","type":"string"}},"required":["text","type"],"title":"InputTextAgentMessageInputContent","type":"object"},{"properties":{"encrypted_content":{"type":"string"},"type":{"enum":["encrypted_content"],"title":"EncryptedContentAgentMessageInputContentType","type":"string"}},"required":["encrypted_content","type"],"title":"EncryptedContentAgentMessageInputContent","type":"object"}]},"ConfigurationReasoning":{"description":"Reasoning settings interpreted by the backend for the routed model.","properties":{"effort":{"${'$'}ref":"#/definitions/ReasoningEffort"}},"required":["effort"],"type":"object"},"ContentItem":{"oneOf":[{"properties":{"text":{"type":"string"},"type":{"enum":["input_text"],"title":"InputTextContentItemType","type":"string"}},"required":["text","type"],"title":"InputTextContentItem","type":"object"},{"anyOf":[{"properties":{"image_url":{"type":"string"}},"required":["image_url"],"title":"ImageUrlContentItem","type":"object"},{"properties":{"file_id":{"type":"string"}},"required":["file_id"],"title":"FileIdContentItem","type":"object"}],"properties":{"detail":{"anyOf":[{"${'$'}ref":"#/definitions/ImageDetail"},{"type":"null"}]},"type":{"enum":["input_image"],"title":"InputImageContentItemType","type":"string"}},"required":["type"],"title":"InputImageContentItem","type":"object"},{"properties":{"audio_url":{"type":"string"},"type":{"enum":["input_audio"],"title":"InputAudioContentItemType","type":"string"}},"required":["audio_url","type"],"title":"InputAudioContentItem","type":"object"},{"properties":{"text":{"type":"string"},"type":{"enum":["output_text"],"title":"OutputTextContentItemType","type":"string"}},"required":["text","type"],"title":"OutputTextContentItem","type":"object"}]},"FunctionCallOutputBody":{"anyOf":[{"type":"string"},{"items":{"${'$'}ref":"#/definitions/FunctionCallOutputContentItem"},"type":"array"}]},"FunctionCallOutputContentItem":{"description":"Responses API compatible content items that can be returned by a tool call. This is a subset of ContentItem with the types we support as function call outputs.","oneOf":[{"properties":{"text":{"type":"string"},"type":{"enum":["input_text"],"title":"InputTextFunctionCallOutputContentItemType","type":"string"}},"required":["text","type"],"title":"InputTextFunctionCallOutputContentItem","type":"object"},{"anyOf":[{"properties":{"image_url":{"type":"string"}},"required":["image_url"],"title":"ImageUrlFunctionCallOutputContentItem","type":"object"},{"properties":{"file_id":{"type":"string"}},"required":["file_id"],"title":"FileIdFunctionCallOutputContentItem","type":"object"}],"properties":{"detail":{"anyOf":[{"${'$'}ref":"#/definitions/ImageDetail"},{"type":"null"}]},"type":{"enum":["input_image"],"title":"InputImageFunctionCallOutputContentItemType","type":"string"}},"required":["type"],"title":"InputImageFunctionCallOutputContentItem","type":"object"},{"properties":{"audio_url":{"type":"string"},"type":{"enum":["input_audio"],"title":"InputAudioFunctionCallOutputContentItemType","type":"string"}},"required":["audio_url","type"],"title":"InputAudioFunctionCallOutputContentItem","type":"object"},{"properties":{"encrypted_content":{"type":"string"},"type":{"enum":["encrypted_content"],"title":"EncryptedContentFunctionCallOutputContentItemType","type":"string"}},"required":["encrypted_content","type"],"title":"EncryptedContentFunctionCallOutputContentItem","type":"object"}]},"ImageDetail":{"enum":["auto","low","high","original"],"type":"string"},"InternalChatMessageMetadataPassthrough":{"description":"Internal Responses API passthrough metadata copied into underlying chat messages.\n\nResponses API strongly types this payload. Do not modify it without first getting API approval and making the corresponding Responses API change.","properties":{"turn_id":{"type":["string","null"]}},"type":"object"},"LocalShellAction":{"oneOf":[{"properties":{"command":{"items":{"type":"string"},"type":"array"},"env":{"additionalProperties":{"type":"string"},"type":["object","null"]},"timeout_ms":{"format":"uint64","minimum":0.0,"type":["integer","null"]},"type":{"enum":["exec"],"title":"ExecLocalShellActionType","type":"string"},"user":{"type":["string","null"]},"working_directory":{"type":["string","null"]}},"required":["command","type"],"title":"ExecLocalShellAction","type":"object"}]},"LocalShellStatus":{"enum":["completed","in_progress","incomplete"],"type":"string"},"MessagePhase":{"description":"Classifies an assistant message as interim commentary or final answer text.\n\nProviders do not emit this consistently, so callers must treat `None` as \"phase unknown\" and keep compatibility behavior for legacy models.","oneOf":[{"description":"Mid-turn assistant text (for example preamble/progress narration).\n\nAdditional tool calls or assistant output may follow before turn completion.","enum":["commentary"],"type":"string"},{"description":"The assistant's terminal answer text for the current turn.","enum":["final_answer"],"type":"string"}]},"ReasoningEffort":{"description":"A non-empty reasoning effort value advertised by the model.","minLength":1,"type":"string"},"ReasoningItemContent":{"oneOf":[{"properties":{"text":{"type":"string"},"type":{"enum":["reasoning_text"],"title":"ReasoningTextReasoningItemContentType","type":"string"}},"required":["text","type"],"title":"ReasoningTextReasoningItemContent","type":"object"},{"properties":{"text":{"type":"string"},"type":{"enum":["text"],"title":"TextReasoningItemContentType","type":"string"}},"required":["text","type"],"title":"TextReasoningItemContent","type":"object"}]},"ReasoningItemReasoningSummary":{"oneOf":[{"properties":{"text":{"type":"string"},"type":{"enum":["summary_text"],"title":"SummaryTextReasoningItemReasoningSummaryType","type":"string"}},"required":["text","type"],"title":"SummaryTextReasoningItemReasoningSummary","type":"object"}]},"ResponseItem":{"oneOf":[{"properties":{"content":{"items":{"${'$'}ref":"#/definitions/ContentItem"},"type":"array"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"phase":{"anyOf":[{"${'$'}ref":"#/definitions/MessagePhase"},{"type":"null"}]},"role":{"type":"string"},"type":{"enum":["message"],"title":"MessageResponseItemType","type":"string"}},"required":["content","role","type"],"title":"MessageResponseItem","type":"object"},{"properties":{"author":{"type":"string"},"content":{"items":{"${'$'}ref":"#/definitions/AgentMessageInputContent"},"type":"array"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"recipient":{"type":"string"},"type":{"enum":["agent_message"],"title":"AgentMessageResponseItemType","type":"string"}},"required":["author","content","recipient","type"],"title":"AgentMessageResponseItem","type":"object"},{"properties":{"content":{"default":null,"items":{"${'$'}ref":"#/definitions/ReasoningItemContent"},"type":["array","null"]},"encrypted_content":{"type":["string","null"]},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"summary":{"items":{"${'$'}ref":"#/definitions/ReasoningItemReasoningSummary"},"type":"array"},"type":{"enum":["reasoning"],"title":"ReasoningResponseItemType","type":"string"}},"required":["summary","type"],"title":"ReasoningResponseItem","type":"object"},{"properties":{"action":{"${'$'}ref":"#/definitions/LocalShellAction"},"call_id":{"description":"Set when using the Responses API.","type":["string","null"]},"id":{"description":"Legacy id field retained for compatibility with older payloads.","type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"status":{"${'$'}ref":"#/definitions/LocalShellStatus"},"type":{"enum":["local_shell_call"],"title":"LocalShellCallResponseItemType","type":"string"}},"required":["action","status","type"],"title":"LocalShellCallResponseItem","type":"object"},{"properties":{"arguments":{"type":"string"},"call_id":{"type":"string"},"encrypted_function_args":{"items":{"type":"string"},"type":["array","null"]},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"name":{"type":"string"},"namespace":{"type":["string","null"]},"type":{"enum":["function_call"],"title":"FunctionCallResponseItemType","type":"string"}},"required":["arguments","call_id","name","type"],"title":"FunctionCallResponseItem","type":"object"},{"properties":{"arguments":true,"call_id":{"type":["string","null"]},"execution":{"type":"string"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"status":{"type":["string","null"]},"type":{"enum":["tool_search_call"],"title":"ToolSearchCallResponseItemType","type":"string"}},"required":["arguments","execution","type"],"title":"ToolSearchCallResponseItem","type":"object"},{"properties":{"call_id":{"type":["string","null"]},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"name":{"type":["string","null"]},"namespace":{"type":["string","null"]},"output":{"${'$'}ref":"#/definitions/FunctionCallOutputBody"},"type":{"enum":["function_call_output"],"title":"FunctionCallOutputResponseItemType","type":"string"}},"required":["output","type"],"title":"FunctionCallOutputResponseItem","type":"object"},{"properties":{"call_id":{"type":"string"},"id":{"type":["string","null"]},"input":{"type":"string"},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"name":{"type":"string"},"namespace":{"type":["string","null"]},"status":{"type":["string","null"]},"type":{"enum":["custom_tool_call"],"title":"CustomToolCallResponseItemType","type":"string"}},"required":["call_id","input","name","type"],"title":"CustomToolCallResponseItem","type":"object"},{"properties":{"call_id":{"type":"string"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"name":{"type":["string","null"]},"output":{"${'$'}ref":"#/definitions/FunctionCallOutputBody"},"type":{"enum":["custom_tool_call_output"],"title":"CustomToolCallOutputResponseItemType","type":"string"}},"required":["call_id","output","type"],"title":"CustomToolCallOutputResponseItem","type":"object"},{"properties":{"call_id":{"type":["string","null"]},"execution":{"type":"string"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"status":{"type":"string"},"tools":{"items":true,"type":"array"},"type":{"enum":["tool_search_output"],"title":"ToolSearchOutputResponseItemType","type":"string"}},"required":["execution","status","tools","type"],"title":"ToolSearchOutputResponseItem","type":"object"},{"properties":{"action":{"anyOf":[{"${'$'}ref":"#/definitions/ResponsesApiWebSearchAction"},{"type":"null"}]},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"status":{"type":["string","null"]},"type":{"enum":["web_search_call"],"title":"WebSearchCallResponseItemType","type":"string"}},"required":["type"],"title":"WebSearchCallResponseItem","type":"object"},{"properties":{"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"result":{"type":"string"},"revised_prompt":{"type":["string","null"]},"status":{"type":"string"},"type":{"enum":["image_generation_call"],"title":"ImageGenerationCallResponseItemType","type":"string"}},"required":["result","status","type"],"title":"ImageGenerationCallResponseItem","type":"object"},{"properties":{"encrypted_content":{"type":"string"},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"type":{"enum":["compaction"],"title":"CompactionResponseItemType","type":"string"}},"required":["encrypted_content","type"],"title":"CompactionResponseItem","type":"object"},{"description":"A durable input control interpreted by the backend at its position in history.","properties":{"reasoning":{"${'$'}ref":"#/definitions/ConfigurationReasoning"},"type":{"enum":["configuration_update"],"title":"ConfigurationUpdateResponseItemType","type":"string"}},"required":["reasoning","type"],"title":"ConfigurationUpdateResponseItem","type":"object"},{"properties":{"type":{"enum":["compaction_trigger"],"title":"CompactionTriggerResponseItemType","type":"string"}},"required":["type"],"title":"CompactionTriggerResponseItem","type":"object"},{"properties":{"encrypted_content":{"type":["string","null"]},"id":{"type":["string","null"]},"internal_chat_message_metadata_passthrough":{"anyOf":[{"${'$'}ref":"#/definitions/InternalChatMessageMetadataPassthrough"},{"type":"null"}]},"type":{"enum":["context_compaction"],"title":"ContextCompactionResponseItemType","type":"string"}},"required":["type"],"title":"ContextCompactionResponseItem","type":"object"},{"properties":{"type":{"enum":["other"],"title":"OtherResponseItemType","type":"string"}},"required":["type"],"title":"OtherResponseItem","type":"object"}]},"ResponsesApiWebSearchAction":{"oneOf":[{"properties":{"queries":{"items":{"type":"string"},"type":["array","null"]},"query":{"type":["string","null"]},"type":{"enum":["search"],"title":"SearchResponsesApiWebSearchActionType","type":"string"}},"required":["type"],"title":"SearchResponsesApiWebSearchAction","type":"object"},{"properties":{"type":{"enum":["open_page"],"title":"OpenPageResponsesApiWebSearchActionType","type":"string"},"url":{"type":["string","null"]}},"required":["type"],"title":"OpenPageResponsesApiWebSearchAction","type":"object"},{"properties":{"pattern":{"type":["string","null"]},"type":{"enum":["find_in_page"],"title":"FindInPageResponsesApiWebSearchActionType","type":"string"},"url":{"type":["string","null"]}},"required":["type"],"title":"FindInPageResponsesApiWebSearchAction","type":"object"},{"properties":{"type":{"enum":["other"],"title":"OtherResponsesApiWebSearchActionType","type":"string"}},"required":["type"],"title":"OtherResponsesApiWebSearchAction","type":"object"}]}},"properties":{"item":{"${'$'}ref":"#/definitions/ResponseItem"},"threadId":{"type":"string"},"turnId":{"type":"string"}},"required":["item","threadId","turnId"],"title":"RawResponseItemCompletedNotification","type":"object"}""",
  )
}

internal class FakeCodexLauncher(val scenario: String = "success", private val failLaunch: Int? = null) {
  data class Child(val process: Process, val directory: Path, val command: List<String>, val environment: Map<String, String>)
  val children = CopyOnWriteArrayList<Child>()
  val directories = CopyOnWriteArraySet<Path>()
  private var launches = 0

  fun launch(command: List<String>, directory: Path, environment: Map<String, String>): Process {
    launches++
    directories.add(directory)
    if ("--out" in command) directories.add(Path.of(command[command.indexOf("--out") + 1]))
    if (launches == failLaunch) throw java.io.IOException("secret-launch-cause")
    val sources = listOf(FakeCodexAppServer::class.java, CodexIsolation::class.java, Json::class.java, JsonElement::class.java, kotlinx.serialization.SerializationException::class.java, kotlin.Unit::class.java, kotlinx.coroutines.CoroutineScope::class.java)
    val classpath = sources.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
    val process = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, FakeCodexAppServer::class.java.name, scenario) + command.drop(1)).directory(directory.toFile()).apply {
      environment().clear()
      environment().putAll(environment)
    }.start()
    children.add(Child(process, directory, command, environment))
    return process
  }

  suspend fun prepare(startupMillis: Long = 30_000): CodexModelBackend = CodexModelBackend.prepare(
    executable = { Path.of("injected-jvm-fake") },
    launch = ::launch,
    host = "Mac OS X",
    environment = mapOf("OPENAI_API_KEY" to "fake-key", "CODEX_API_KEY" to "fake-key", "CODEX_HOME" to "/unused-codex-owned-home", "CODEX_SQLITE_HOME" to "/unused-codex-owned-database"),
    startupMillis = startupMillis,
  )

  suspend fun verifyCleanup() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    children.forEach { child -> assertk.assertThat(child.process.isAlive).isEqualTo(false) }
    directories.forEach { directory -> assertk.assertThat(Files.exists(directory)).isEqualTo(false) }
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
  }
}

/** Receipt output is caller-owned and opt-in; ordinary test runs write no external journal. */
internal suspend fun recordCodexTestEvidence(evidence: JsonObject) {
  val path = System.getenv("VERITY_CODEX_TEST_EVIDENCE")?.let(Path::of) ?: return
  kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    Files.writeString(path, evidence.toString() + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
  }
}
