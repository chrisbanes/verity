package me.chrisbanes.verity.cli

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

internal typealias CodexFailureKind = me.chrisbanes.verity.agent.ModelBackendFailureKind

internal typealias CodexFailure = me.chrisbanes.verity.agent.ModelBackendFailure

internal data class CodexThreadEchoFields(val responseArrays: Set<String>, val threadArrays: Set<String>)

internal data class CodexIsolation(val names: Map<String, Set<String>> = emptyMap()) {
  val policy: Map<String, JsonElement> = buildMap {
    put("model_provider", JsonPrimitive("openai"))
    put("service_tier", JsonPrimitive("default"))
    put("approval_policy", JsonPrimitive("never"))
    put("approvals_reviewer", JsonPrimitive("user"))
    put("sandbox_mode", JsonPrimitive("read-only"))
    put("web_search", JsonPrimitive("disabled"))
    put("forced_login_method", JsonPrimitive("chatgpt"))
    put("agents.enabled", JsonPrimitive(false))
    put("notify", JsonArray(emptyList()))
    put("apps._default.enabled", JsonPrimitive(false))
    put("project_doc_max_bytes", JsonPrimitive(0))
    put("developer_instructions", JsonPrimitive(""))
    features.forEach { put("features.$it", JsonPrimitive(false)) }
    listOf("tools.update_plan.enabled", "tools.experimental_request_user_input.enabled", "cloud.skills.enabled", "skills.include_instructions").forEach { put(it, JsonPrimitive(false)) }
  }

  fun arguments(): List<String> = buildList {
    (policy + namedPolicy()).forEach { (key, value) ->
      add("-c")
      add("$key=$value")
    }
  }

  fun threadConfig(): JsonObject = JsonObject(policy + namedPolicy())

  private fun namedPolicy(): Map<String, JsonElement> = buildMap {
    names.forEach { (group, keys) -> keys.forEach { put("$group.${tomlKey(it)}.enabled", JsonPrimitive(false)) } }
  }

  fun verify(config: JsonObject): CodexIsolation {
    policy.forEach { (key, expected) -> if (lookup(config, key.split('.')) != expected) throw CodexFailure(CodexFailureKind.ISOLATION) }
    val discovered = groups.associateWith { group ->
      val objectValue = config[group]
      if (objectValue != null && objectValue !is JsonObject) throw CodexFailure(CodexFailureKind.ISOLATION)
      objectValue?.keys.orEmpty() - if (group == "apps") setOf("_default") else emptySet()
    }
    if (names.isNotEmpty()) {
      if (discovered != names) throw CodexFailure(CodexFailureKind.ISOLATION)
      names.forEach { (group, keys) -> keys.forEach { if (lookup(config, listOf(group, it, "enabled")) != JsonPrimitive(false)) throw CodexFailure(CodexFailureKind.ISOLATION) } }
    }
    return CodexIsolation(discovered)
  }

  companion object {
    val groups = listOf("mcp_servers", "plugins", "apps")
    val features = "apps plugins remote_plugin plugin_sharing hooks memories shell_tool unified_exec shell_snapshot multi_agent multi_agent_v2 code_mode code_mode_host code_mode_only browser_use browser_use_external browser_use_full_cdp_access computer_use in_app_browser image_generation view_image skill_search skill_mcp_dependency_install tool_suggest default_mode_request_user_input sleep_tool goals workspace_dependencies realtime_conversation in_app_local_automation prevent_idle_sleep request_permissions_tool context_management current_time_reminder deferred_executor standalone_web_search token_budget".split(' ')
    fun tomlKey(value: String): String = JsonPrimitive(value).toString()
    private fun lookup(config: JsonElement, path: List<String>): JsonElement? = path.fold(config as JsonElement?) { value, key -> (value as? JsonObject)?.get(key) }

    suspend fun validateSchema(directory: Path) = withContext(Dispatchers.IO) {
      fun schema(name: String): JsonObject {
        val path = directory.resolve(name)
        if (generateSequence(path) { it.parent }.takeWhile { it != directory.parent }.any { Files.isSymbolicLink(it) } || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > CodexAppServerClient.MAX_FRAME_BYTES) throw CodexFailure(CodexFailureKind.PROTOCOL)
        return Json.parseToJsonElement(Files.readString(path)).jsonObject
      }
      fun hasType(value: JsonElement?, type: String): Boolean {
        val declared = (value as? JsonObject)?.get("type")
        return declared == JsonPrimitive(type) || (declared is JsonArray && JsonPrimitive(type) in declared)
      }
      val start = schema("v2/ThreadStartParams.json")
      val properties = start["properties"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      listOf("experimentalRawEvents", "allowProviderModelFallback", "ephemeral").forEach { if (!hasType(properties[it], "boolean")) throw CodexFailure(CodexFailureKind.PROTOCOL) }
      mapOf("dynamicTools" to "DynamicToolSpec", "environments" to "TurnEnvironmentParams", "runtimeWorkspaceRoots" to "AbsolutePathBuf", "selectedCapabilityRoots" to "SelectedCapabilityRoot").forEach { (key, definition) ->
        val field = properties[key] as? JsonObject
        if (!hasType(field, "array") || field?.get("items")?.jsonObject?.get("\$ref") != JsonPrimitive("#/definitions/$definition") || start["definitions"]?.jsonObject?.containsKey(definition) != true) throw CodexFailure(CodexFailureKind.PROTOCOL)
      }
      val capabilities = schema("v1/InitializeParams.json")["definitions"]?.jsonObject?.get("InitializeCapabilities")?.jsonObject?.get("properties")?.jsonObject
      listOf("experimentalApi", "explicitGatewayOauth", "requestAttestation").forEach { if (!hasType(capabilities?.get(it), "boolean")) throw CodexFailure(CodexFailureKind.PROTOCOL) }
      val raw = schema("v2/RawResponseItemCompletedNotification.json")
      if (raw["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.containsAll(listOf("item", "threadId", "turnId")) != true || raw["properties"]?.jsonObject?.get("item")?.jsonObject?.get("\$ref") != JsonPrimitive("#/definitions/ResponseItem")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      fun reference(value: JsonElement?, name: String): Boolean = (value as? JsonObject)?.get("\$ref") == JsonPrimitive("#/definitions/$name")
      val rawDefinitions = raw["definitions"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      val rawVariants = rawDefinitions["ResponseItem"]?.jsonObject?.get("oneOf")?.jsonArray ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      val variantTypes = rawVariants.map { variant ->
        val type = variant.jsonObject["properties"]?.jsonObject?.get("type")?.jsonObject
        if (!hasType(type, "string")) throw CodexFailure(CodexFailureKind.PROTOCOL)
        type?.get("enum")?.jsonArray?.singleOrNull()?.jsonPrimitive?.content ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      }
      if (!variantTypes.containsAll(listOf("custom_tool_call", "custom_tool_call_output", "function_call", "function_call_output", "local_shell_call", "web_search_call"))) throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (!hasType(raw["properties"]?.jsonObject?.get("threadId"), "string") || !hasType(raw["properties"]?.jsonObject?.get("turnId"), "string")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      val responseSchema = schema("v2/ThreadStartResponse.json")
      val response = responseSchema["properties"]?.jsonObject
      val definitions = responseSchema["definitions"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (!hasType(response?.get("runtimeWorkspaceRoots"), "array") || !hasType(response?.get("model"), "string") || !hasType(response?.get("modelProvider"), "string") || !reference(response?.get("cwd"), "AbsolutePathBuf") || !hasType(definitions["AbsolutePathBuf"], "string") || !reference(response?.get("approvalPolicy"), "AskForApproval") || !reference(response?.get("thread"), "Thread")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      val approvalVariants = definitions["AskForApproval"]?.jsonObject?.get("oneOf")?.jsonArray
      if (approvalVariants?.any { variant -> hasType(variant, "string") && JsonPrimitive("never") in variant.jsonObject["enum"]!!.jsonArray } != true) throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (response?.get("sandbox")?.jsonObject?.get("allOf")?.jsonArray?.singleOrNull()?.let { reference(it, "SandboxPolicy") } != true) throw CodexFailure(CodexFailureKind.PROTOCOL)
      val sandboxVariants = definitions["SandboxPolicy"]?.jsonObject?.get("oneOf")?.jsonArray
      if (sandboxVariants?.any { variant ->
          val type = variant.jsonObject["properties"]?.jsonObject?.get("type")?.jsonObject
          hasType(type, "string") && type?.get("enum")?.jsonArray?.contains(JsonPrimitive("readOnly")) == true
        } != true
      ) {
        throw CodexFailure(CodexFailureKind.PROTOCOL)
      }
      val thread = definitions["Thread"]?.jsonObject
      if (!hasType(thread, "object") || !hasType(thread?.get("properties")?.jsonObject?.get("id"), "string") || !hasType(thread?.get("properties")?.jsonObject?.get("ephemeral"), "boolean")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      val threadProperties = thread?.get("properties")?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      listOf("model", "modelProvider").forEach { if (!hasType(threadProperties[it], "string")) throw CodexFailure(CodexFailureKind.PROTOCOL) }
      if (!hasType(threadProperties["environments"], "array")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      listOf("model", "modelProvider", "cwd", "baseInstructions", "developerInstructions").forEach { if (!hasType(properties[it], "string")) throw CodexFailure(CodexFailureKind.PROTOCOL) }
      if (!hasType(properties["config"], "object")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      val startDefinitions = start["definitions"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (properties["approvalPolicy"]?.jsonObject?.get("anyOf")?.jsonArray?.any { reference(it, "AskForApproval") } != true || properties["sandbox"]?.jsonObject?.get("anyOf")?.jsonArray?.any { reference(it, "SandboxMode") } != true || properties["approvalsReviewer"]?.jsonObject?.get("anyOf")?.jsonArray?.any { reference(it, "ApprovalsReviewer") } != true) throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (startDefinitions["SandboxMode"]?.jsonObject?.get("enum")?.jsonArray?.contains(JsonPrimitive("read-only")) != true || startDefinitions["ApprovalsReviewer"]?.jsonObject?.get("enum")?.jsonArray?.contains(JsonPrimitive("user")) != true || definitions["ApprovalsReviewer"]?.jsonObject?.get("enum")?.jsonArray?.contains(JsonPrimitive("user")) != true) throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (startDefinitions["AskForApproval"]?.jsonObject?.get("oneOf")?.jsonArray?.any { it.jsonObject["enum"]?.jsonArray?.contains(JsonPrimitive("never")) == true } != true) throw CodexFailure(CodexFailureKind.PROTOCOL)
      val turnStart = schema("v2/TurnStartParams.json")
      val turnProperties = turnStart["properties"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (!hasType(turnProperties["threadId"], "string") || !hasType(turnProperties["serviceTierForTurn"], "string") || !hasType(turnProperties["input"], "array") || !reference(turnProperties["input"]?.jsonObject?.get("items"), "UserInput")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (turnProperties["effort"]?.jsonObject?.get("anyOf")?.jsonArray?.any { reference(it, "ReasoningEffort") } != true) throw CodexFailure(CodexFailureKind.PROTOCOL)
      val inputs = turnStart["definitions"]?.jsonObject?.get("UserInput")?.jsonObject?.get("oneOf")?.jsonArray ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      mapOf("text" to "text", "localImage" to "path").forEach { (type, field) ->
        val variant = inputs.singleOrNull { it.jsonObject["properties"]?.jsonObject?.get("type")?.jsonObject?.get("enum") == JsonArray(listOf(JsonPrimitive(type))) }?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        if (!hasType(variant["properties"]?.jsonObject?.get(field), "string")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      }
      listOf("ItemStartedNotification", "ItemCompletedNotification").forEach { name ->
        val event = schema("v2/$name.json")
        val fields = event["properties"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        if (!hasType(fields["threadId"], "string") || !hasType(fields["turnId"], "string") || !reference(fields["item"], "ThreadItem")) throw CodexFailure(CodexFailureKind.PROTOCOL)
        val eventDefinitions = event["definitions"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        val variants = eventDefinitions["ThreadItem"]?.jsonObject?.get("oneOf")?.jsonArray ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        val assistant = variants.singleOrNull { it.jsonObject["properties"]?.jsonObject?.get("type")?.jsonObject?.get("enum") == JsonArray(listOf(JsonPrimitive("agentMessage"))) }?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        val fieldsAssistant = assistant["properties"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        if (!hasType(fieldsAssistant["id"], "string") || !hasType(fieldsAssistant["text"], "string") || fieldsAssistant["phase"]?.jsonObject?.get("anyOf")?.jsonArray?.any { reference(it, "MessagePhase") } != true) throw CodexFailure(CodexFailureKind.PROTOCOL)
        val phases = eventDefinitions["MessagePhase"]?.jsonObject?.get("oneOf")?.jsonArray ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        if (listOf("commentary", "final_answer").any { phase -> phases.none { it.jsonObject["enum"] == JsonArray(listOf(JsonPrimitive(phase))) && hasType(it, "string") } }) throw CodexFailure(CodexFailureKind.PROTOCOL)
      }
      listOf("TurnStartResponse", "TurnStartedNotification", "TurnCompletedNotification").forEach { name ->
        val event = schema("v2/$name.json")
        val fields = event["properties"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        if (!reference(fields["turn"], "Turn") || (name != "TurnStartResponse" && !hasType(fields["threadId"], "string"))) throw CodexFailure(CodexFailureKind.PROTOCOL)
        val eventDefinitions = event["definitions"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        val turnFields = eventDefinitions["Turn"]?.jsonObject?.get("properties")?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        if (!hasType(turnFields["id"], "string") || !hasType(turnFields["items"], "array") || !reference(turnFields["status"], "TurnStatus")) throw CodexFailure(CodexFailureKind.PROTOCOL)
        val statuses = eventDefinitions["TurnStatus"]?.jsonObject?.get("enum")?.jsonArray ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        if (!statuses.containsAll(listOf("completed", "failed", "interrupted", "inProgress").map(::JsonPrimitive))) throw CodexFailure(CodexFailureKind.PROTOCOL)
      }
      val statusNotification = schema("v2/ThreadStatusChangedNotification.json")
      val statusFields = statusNotification["properties"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (!hasType(statusFields["threadId"], "string") || !reference(statusFields["status"], "ThreadStatus")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      val statusDefinitions = statusNotification["definitions"]?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      val statusVariants = statusDefinitions["ThreadStatus"]?.jsonObject?.get("oneOf")?.jsonArray ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      listOf("idle", "active", "notLoaded", "systemError").forEach { type ->
        val variant = statusVariants.singleOrNull { it.jsonObject["properties"]?.jsonObject?.get("type")?.jsonObject?.get("enum") == JsonArray(listOf(JsonPrimitive(type))) }?.jsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        if (type == "active" && !hasType(variant["properties"]?.jsonObject?.get("activeFlags"), "array")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      }
      val unsubscribe = schema("v2/ThreadUnsubscribeResponse.json")
      if (!reference(unsubscribe["properties"]?.jsonObject?.get("status"), "ThreadUnsubscribeStatus") || unsubscribe["definitions"]?.jsonObject?.get("ThreadUnsubscribeStatus")?.jsonObject?.get("enum")?.jsonArray?.contains(JsonPrimitive("unsubscribed")) != true) throw CodexFailure(CodexFailureKind.PROTOCOL)
      val optionalEmptyArrays = setOf("selectedCapabilityRoots", "dynamicTools", "environments")
      val responseArrays = optionalEmptyArrays.filterTo(mutableSetOf()) { response.containsKey(it) }
      val threadArrays = optionalEmptyArrays.filterTo(mutableSetOf()) { threadProperties.containsKey(it) }
      responseArrays.forEach { if (!hasType(response[it], "array")) throw CodexFailure(CodexFailureKind.PROTOCOL) }
      threadArrays.forEach { if (!hasType(threadProperties[it], "array")) throw CodexFailure(CodexFailureKind.PROTOCOL) }
      CodexThreadEchoFields(responseArrays, threadArrays)
    }
  }
}
