package me.chrisbanes.verity.cli

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One prepared process with serialized, isolated ephemeral model requests. */
internal class CodexModelBackend private constructor(
  internal val client: CodexAppServerClient,
  internal val isolation: CodexIsolation,
  internal val workingDirectory: Path,
  internal val resources: CodexOwnedResources,
  private val echoFields: CodexThreadEchoFields,
) : ModelRequestBackend {
  private val requestMutex = Mutex()
  private val stateLock = Any()
  private val requestJob = SupervisorJob()
  private val requestScope = CoroutineScope(requestJob + Dispatchers.IO)
  private var closed = false
  private var active: Deferred<Message.Assistant>? = null
  private val acquiredThreadIds = mutableSetOf<String>()

  override suspend fun execute(request: ModelRequest): Message.Assistant = requestMutex.withLock {
    val owned = synchronized(stateLock) {
      if (closed) throw CodexFailure(CodexFailureKind.REQUEST)
      requestScope.async(start = CoroutineStart.LAZY) { executeOwned(request) }.also { active = it }
    }
    try {
      owned.start()
      owned.await()
    } catch (e: CancellationException) {
      val callerCancelled = !currentCoroutineContext().isActive
      cancelOwned(owned, e)
      if (callerCancelled) throw e
      throw CodexFailure(CodexFailureKind.REQUEST)
    } finally {
      synchronized(stateLock) { if (active === owned) active = null }
    }
  }

  private suspend fun cancelOwned(owned: Deferred<*>, cancellation: CancellationException) = withContext(NonCancellable) {
    val deadline = resources.beginCleanup()
    synchronized(stateLock) { closed = true }
    owned.cancel(cancellation)
    withTimeoutOrNull(minOf(400L, deadline.remainingMillis())) { owned.join() }
    try {
      resources.close(deadline)
    } catch (_: Exception) {
      cancellation.addSuppressed(CodexFailure(CodexFailureKind.CLEANUP))
    }
    withTimeoutOrNull(deadline.remainingMillis()) { owned.join() }
  }

  override suspend fun close() = withContext(NonCancellable) {
    val owned = synchronized(stateLock) {
      closed = true
      active
    }
    val deadline = resources.beginCleanup()
    if (owned != null) cancelOwned(owned, CancellationException("Codex backend closed"))
    try {
      resources.close(deadline)
      if (owned?.isCompleted == false) throw CodexFailure(CodexFailureKind.CLEANUP)
      requestJob.cancel()
      if (!requestJob.isCompleted && withTimeoutOrNull(deadline.remainingMillis()) {
          requestJob.cancelAndJoin()
          true
        } != true
      ) {
        throw CodexFailure(CodexFailureKind.CLEANUP)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      requestJob.cancel()
      throw CodexFailure(CodexFailureKind.CLEANUP)
    }
  }

  suspend fun validateRoles(roles: List<Pair<SelectedRoleModel.Codex, String?>>) {
    val account = client.request("account/read", buildJsonObject { put("refreshToken", false) })["account"] as? JsonObject
    if (account?.get("type") != JsonPrimitive("chatgpt")) throw CodexFailure(CodexFailureKind.AUTH)
    val models = linkedMapOf<String, JsonObject>()
    val cursors = mutableSetOf<String>()
    var cursor: String? = null
    var pages = 0
    do {
      if (++pages > 100) throw CodexFailure(CodexFailureKind.PROTOCOL)
      val response = client.request(
        "model/list",
        buildJsonObject {
          put("limit", 100)
          put("includeHidden", true)
          cursor?.let { put("cursor", it) }
        },
      )
      val data = response["data"] as? JsonArray ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      for (value in data) {
        val entry = value as? JsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        val id = (entry["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        if (id.isBlank() || models.put(id, entry) != null) throw CodexFailure(CodexFailureKind.PROTOCOL)
      }
      val next = response["nextCursor"] ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      cursor = if (next == JsonNull) null else (next as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (cursor != null && (cursor.isBlank() || !cursors.add(cursor))) throw CodexFailure(CodexFailureKind.PROTOCOL)
    } while (cursor != null)
    for ((index, role) in roles.withIndex()) {
      val (model, effort) = role
      val entry = models[model.modelId] ?: throw CodexFailure(CodexFailureKind.MODEL)
      val modalities = entry["inputModalities"] as? JsonArray ?: throw CodexFailure(CodexFailureKind.MODALITY)
      val required = if (index == 0) setOf("text") else setOf("text", "image")
      if (!modalities.containsAll(required.map(::JsonPrimitive))) throw CodexFailure(CodexFailureKind.MODALITY)
      val supported = entry["supportedReasoningEfforts"] as? JsonArray ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
      val efforts = supported.map { ((it as? JsonObject)?.get("reasoningEffort") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw CodexFailure(CodexFailureKind.PROTOCOL) }
      if (efforts.any { it.isBlank() } || efforts.distinct().size != efforts.size) throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (effort != null && effort !in efforts) throw CodexFailure(CodexFailureKind.EFFORT)
      val session = RequestSession(ModelRequest("preflight", "", "", emptyList(), model, ModelRequestSettings.Codex(effort)))
      try {
        session.startThread()
        session.unsubscribe()
      } finally {
        client.clearDiscardedCorrelation()
      }
    }
  }

  private suspend fun executeOwned(request: ModelRequest): Message.Assistant {
    var session: RequestSession? = null
    try {
      val ownedSession = RequestSession(request).also { session = it }
      val result = ownedSession.execute()
      val deadline = CodexCleanupDeadline()
      val cleanup = resources.scope.async { ownedSession.unsubscribe() }
      try {
        withContext(NonCancellable) {
          if (withTimeoutOrNull(minOf(400L, deadline.remainingMillis())) {
              cleanup.await()
              true
            } != true
          ) {
            cleanup.cancel()
            resources.beginCleanup(deadline)
            throw CodexFailure(CodexFailureKind.CLEANUP)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        resources.beginCleanup(deadline)
        throw CodexFailure(CodexFailureKind.CLEANUP)
      }
      return result
    } catch (e: CancellationException) {
      synchronized(stateLock) { closed = true }
      try {
        resources.close(resources.beginCleanup()) { session?.cleanup() }
      } catch (_: Exception) {
        e.addSuppressed(CodexFailure(CodexFailureKind.CLEANUP))
      }
      throw e
    } catch (e: Exception) {
      val primary = if (e is CodexFailure) e else CodexFailure(CodexFailureKind.PROTOCOL)
      synchronized(stateLock) { closed = true }
      try {
        resources.close(resources.beginCleanup()) { session?.cleanup() }
      } catch (_: Exception) {
        primary.addSuppressed(CodexFailure(CodexFailureKind.CLEANUP))
      }
      throw primary
    }
  }

  /** Only this request, then its owned cleanup callback, consumes the transport. */
  private inner class RequestSession(private val request: ModelRequest) {
    private val model = (request.model as? SelectedRoleModel.Codex)?.modelId ?: throw CodexFailure(CodexFailureKind.REQUEST)
    private val settings = request.settings as? ModelRequestSettings.Codex ?: throw CodexFailure(CodexFailureKind.REQUEST)
    private var threadId: String? = null
    private var observedThreadId: String? = null
    private var turnId: String? = null
    private var rejected = false
    private var interrupted = false
    private var unsubscribeAttempted = false
    private var terminal = false
    private var turnStartInitiated = false
    private var finalText: String? = null
    private var legacyText: String? = null

    suspend fun startThread() {
      client.correlateDiscarded(null, null)
      val start = client.request(
        "thread/start",
        buildJsonObject {
          put("model", model)
          put("modelProvider", "openai")
          put("allowProviderModelFallback", false)
          put("experimentalRawEvents", true)
          put("ephemeral", true)
          put("approvalPolicy", "never")
          put("approvalsReviewer", "user")
          put("sandbox", "read-only")
          put("cwd", workingDirectory.toString())
          put("baseInstructions", request.systemText)
          put("developerInstructions", "")
          put("config", isolation.threadConfig())
          listOf("runtimeWorkspaceRoots", "selectedCapabilityRoots", "dynamicTools", "environments").forEach { put(it, JsonArray(emptyList())) }
        },
        ::event,
      )
      val thread = start["thread"] as? JsonObject ?: reject()
      val candidateThreadId = string(thread, "id")
      if (observedThreadId != null && observedThreadId != candidateThreadId) reject()
      if (candidateThreadId in acquiredThreadIds) reject()
      mapOf("model" to model, "modelProvider" to "openai", "cwd" to workingDirectory.toString(), "approvalPolicy" to "never", "approvalsReviewer" to "user").forEach { (key, expected) -> if (start[key] != JsonPrimitive(expected)) reject() }
      if (start["runtimeWorkspaceRoots"] != JsonArray(emptyList())) reject()
      (echoFields.responseArrays + setOf("selectedCapabilityRoots", "dynamicTools", "environments").filter { start.containsKey(it) }).forEach { if (start[it] != JsonArray(emptyList())) reject() }
      val sandbox = start["sandbox"] as? JsonObject ?: reject()
      if (sandbox["type"] != JsonPrimitive("readOnly") || sandbox["networkAccess"] != JsonPrimitive(false)) reject()
      verifyThread(thread)
      acquiredThreadIds.add(candidateThreadId)
      threadId = candidateThreadId
      client.correlateDiscarded(threadId, turnId)
    }

    suspend fun execute(): Message.Assistant {
      startThread()
      turnStartInitiated = true
      val response = client.request(
        "turn/start",
        buildJsonObject {
          put("threadId", threadId)
          put("serviceTierForTurn", "default")
          settings.reasoningEffort?.let { put("effort", it) }
          put(
            "input",
            JsonArray(
              buildList {
                add(textInput(request.userText))
                request.images.forEach { image ->
                  add(textInput(image.label))
                  add(
                    buildJsonObject {
                      put("type", "localImage")
                      put("path", image.path.toAbsolutePath().toString())
                    },
                  )
                }
              },
            ),
          )
        },
        ::event,
      )
      val turn = response["turn"] as? JsonObject ?: reject()
      identifyTurn(turn)
      if (turn["status"] != JsonPrimitive("inProgress")) reject()
      checkTurnItems(turn)
      while (!terminal) event(client.receive())
      if (rejected) reject()
      val text = finalText ?: legacyText ?: throw CodexFailure(CodexFailureKind.REQUEST)
      if (text.isBlank()) throw CodexFailure(CodexFailureKind.REQUEST)
      return Message.Assistant(
        content = text,
        metaInfo = ResponseMetaInfo(Clock.System.now(), modelId = model),
        finishReason = "stop",
        id = turnId,
      )
    }

    private fun textInput(value: String) = buildJsonObject {
      put("type", "text")
      put("text", value)
    }

    private fun string(value: JsonObject, key: String): String = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() } ?: reject()

    private fun reject(): Nothing {
      rejected = true
      throw CodexFailure(CodexFailureKind.PROTOCOL)
    }

    private fun verifyThread(thread: JsonObject) {
      if (thread["ephemeral"] != JsonPrimitive(true) || thread["environments"] != JsonArray(emptyList()) || thread["model"] != JsonPrimitive(model) || thread["modelProvider"] != JsonPrimitive("openai") || thread["cwd"] != JsonPrimitive(workingDirectory.toString())) reject()
      (echoFields.threadArrays + setOf("selectedCapabilityRoots", "dynamicTools", "environments").filter { thread.containsKey(it) }).forEach { if (thread[it] != JsonArray(emptyList())) reject() }
      val id = string(thread, "id")
      if (id in acquiredThreadIds && id != threadId) reject()
      if (threadId != null && id != threadId) reject()
      if (observedThreadId != null && id != observedThreadId) reject()
      observedThreadId = id
      client.correlateDiscarded(threadId ?: observedThreadId, turnId)
    }

    private fun identifyTurn(turn: JsonObject) {
      val id = string(turn, "id")
      if (turnId != null && turnId != id) reject()
      turnId = id
      client.correlateDiscarded(threadId, turnId)
    }

    private fun checkTurnItems(turn: JsonObject) {
      val items = turn["items"] as? JsonArray ?: reject()
      items.forEach { item(it as? JsonObject ?: reject(), completed = false) }
    }

    private suspend fun event(frame: JsonObject) {
      try {
        if (frame.containsKey("id")) reject()
        val method = string(frame, "method")
        val params = frame["params"] as? JsonObject ?: reject()
        if (method == "thread/started") {
          verifyThread(params["thread"] as? JsonObject ?: reject())
          return
        }
        if (params["threadId"] != JsonPrimitive(threadId ?: observedThreadId ?: reject())) reject()
        if (method != "thread/status/changed" && !turnStartInitiated) reject()
        when (method) {
          "turn/started", "turn/completed" -> {
            val turn = params["turn"] as? JsonObject ?: reject()
            identifyTurn(turn)
            checkTurnItems(turn)
            if (method == "turn/completed") {
              if (turn["status"] != JsonPrimitive("completed") || (turn["error"] != null && turn["error"] != JsonNull)) throw CodexFailure(CodexFailureKind.REQUEST)
              terminal = true
            } else if (turn["status"] != JsonPrimitive("inProgress")) {
              reject()
            }
          }

          "item/started", "item/completed", "rawResponseItem/completed" -> {
            if (params["turnId"] != JsonPrimitive(turnId ?: reject())) reject()
            val value = params["item"] as? JsonObject ?: reject()
            if (method == "rawResponseItem/completed") {
              rawItem(value)
            } else {
              item(value, method == "item/completed")
            }
          }

          "thread/status/changed" -> verifyStatus(params, cleanup = false)

          "item/agentMessage/delta", "item/reasoning/textDelta", "item/reasoning/summaryTextDelta", "thread/tokenUsage/updated" -> {
            if (params["turnId"] != JsonPrimitive(turnId ?: reject())) reject()
          }

          else -> reject()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        rejected = true
        throw e
      }
    }

    private fun verifyStatus(params: JsonObject, cleanup: Boolean) {
      val status = params["status"] as? JsonObject ?: reject()
      val flags = status["activeFlags"]
      if (flags != null && flags != JsonArray(emptyList())) reject()
      when (string(status, "type")) {
        "idle" -> Unit
        "active" -> if (flags != JsonArray(emptyList())) reject()
        "notLoaded" -> if (!cleanup || (!terminal && !rejected)) throw CodexFailure(CodexFailureKind.REQUEST)
        "systemError" -> throw CodexFailure(CodexFailureKind.REQUEST)
        else -> reject()
      }
    }

    private fun rawItem(value: JsonObject) {
      when (string(value, "type")) {
        "message" -> {
          if (string(value, "role") !in setOf("assistant", "user", "system", "developer")) reject()
          val content = value["content"] as? JsonArray ?: reject()
          content.forEach { element ->
            val part = element as? JsonObject ?: reject()
            when (string(part, "type")) {
              "input_text", "output_text" -> if ((part["text"] as? JsonPrimitive)?.isString != true) reject()
              "input_image" -> if ((part["image_url"] as? JsonPrimitive)?.isString != true) reject()
              else -> reject()
            }
          }
        }

        "reasoning" -> if (value["summary"] !is JsonArray) reject()

        "compaction" -> if ((value["encrypted_content"] as? JsonPrimitive)?.isString != true) reject()

        "context_compaction" -> if (value["encrypted_content"] != null && value["encrypted_content"] != JsonNull && (value["encrypted_content"] as? JsonPrimitive)?.isString != true) reject()

        "compaction_trigger" -> Unit

        else -> reject()
      }
    }

    private fun item(value: JsonObject, completed: Boolean) {
      when (string(value, "type")) {
        "agentMessage" -> {
          string(value, "id")
          val text = (value["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: reject()
          if (text.toByteArray(Charsets.UTF_8).size > 1024 * 1024) reject()
          val phase = value["phase"]
          if (phase != null && phase != JsonNull && phase !in setOf(JsonPrimitive("commentary"), JsonPrimitive("final_answer"))) reject()
          if (completed && !rejected) {
            when (phase) {
              JsonPrimitive("final_answer") -> finalText = text
              null, JsonNull -> legacyText = text
              else -> Unit
            }
          }
        }

        "userMessage", "reasoning", "contextCompaction" -> {
          string(value, "id")
        }

        else -> reject()
      }
    }

    suspend fun unsubscribe() {
      val ownedThreadId = threadId ?: observedThreadId ?: return
      if (unsubscribeAttempted) return
      unsubscribeAttempted = true
      val response = client.request("thread/unsubscribe", buildJsonObject { put("threadId", ownedThreadId) }, ::cleanupEvent)
      if (response["status"] != JsonPrimitive("unsubscribed")) throw CodexFailure(CodexFailureKind.CLEANUP)
      client.requireHealthy()
      client.clearDiscardedCorrelation()
    }

    suspend fun cleanup() {
      rejected = true
      var failed = false
      if (turnId != null && threadId != null && !interrupted && !terminal) {
        if (!client.ownsTurn(threadId!!, turnId!!)) throw CodexFailure(CodexFailureKind.CLEANUP)
        interrupted = true
        try {
          client.request(
            "turn/interrupt",
            buildJsonObject {
              put("threadId", threadId)
              put("turnId", turnId)
            },
            ::cleanupEvent,
          )
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          failed = true
        }
      }
      try {
        unsubscribe()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        failed = true
      }
      if (failed) throw CodexFailure(CodexFailureKind.CLEANUP)
    }

    private suspend fun cleanupEvent(frame: JsonObject) {
      // Permissive draining is limited to a request that was already rejected.
      val params = frame["params"] as? JsonObject ?: throw CodexFailure(CodexFailureKind.CLEANUP)
      if (params["threadId"] != JsonPrimitive(threadId ?: observedThreadId)) throw CodexFailure(CodexFailureKind.CLEANUP)
      when ((frame["method"] as? JsonPrimitive)?.contentOrNull) {
        "item/started", "item/completed", "rawResponseItem/completed" -> {
          if (params["turnId"] != JsonPrimitive(turnId)) throw CodexFailure(CodexFailureKind.CLEANUP)
          if (!rejected) {
            val value = params["item"] as? JsonObject ?: reject()
            if (frame["method"] == JsonPrimitive("rawResponseItem/completed")) rawItem(value) else item(value, completed = false)
          }
        }

        "turn/started", "turn/completed" -> {
          val turn = params["turn"] as? JsonObject ?: throw CodexFailure(CodexFailureKind.CLEANUP)
          if (turn["id"] != JsonPrimitive(turnId)) throw CodexFailure(CodexFailureKind.CLEANUP)
          if (!rejected) {
            if (frame["method"] == JsonPrimitive("turn/started") || turn["status"] != JsonPrimitive("completed") || (turn["error"] != null && turn["error"] != JsonNull)) reject()
            checkTurnItems(turn)
          }
        }

        "thread/status/changed" -> verifyStatus(params, cleanup = true)

        "item/agentMessage/delta", "item/reasoning/textDelta", "item/reasoning/summaryTextDelta", "thread/tokenUsage/updated" -> {
          if (params["turnId"] != JsonPrimitive(turnId)) throw CodexFailure(CodexFailureKind.CLEANUP)
        }

        else -> throw CodexFailure(CodexFailureKind.CLEANUP)
      }
    }
  }

  companion object {
    /** API keys, plus environment overrides that redirect ChatGPT credential or request origins outside config/read. */
    internal val removedEnvironment = setOf("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL", "CODEX_APP_SERVER_CHATGPT_BASE_URL", "CODEX_REFRESH_TOKEN_URL_OVERRIDE", "CODEX_REVOKE_TOKEN_URL_OVERRIDE")

    suspend fun prepare(
      executable: suspend () -> Path? = { findExecutable() },
      launch: (List<String>, Path, Map<String, String>) -> Process = ::launchProcess,
      host: String = System.getProperty("os.name"),
      environment: Map<String, String> = System.getenv(),
      startupMillis: Long = 30_000,
    ): CodexModelBackend {
      val resources = CodexOwnedResources()
      try {
        return withTimeout(startupMillis) {
          if (host != "Mac OS X") throw CodexFailure(CodexFailureKind.HOST)
          val binary = executable() ?: throw CodexFailure(CodexFailureKind.INSTALLATION)
          val childEnvironment = environment - removedEnvironment
          val versionDirectory = resources.directory()
          val versionProcess = resources.launch(listOf(binary.toString(), "--version"), versionDirectory, childEnvironment, launch)
          val versionOutput = resources.scope.async { CodexAppServerClient.readFrame(versionProcess.inputStream) }.await()
          awaitExit(versionProcess)
          if (versionProcess.exitValue() != 0 || !supportedVersion(versionOutput)) throw CodexFailure(CodexFailureKind.VERSION)
          val schemaDirectory = resources.directory()
          val schemaWorkingDirectory = resources.directory()
          val schemaProcess = resources.launch(listOf(binary.toString(), "app-server", "generate-json-schema", "--experimental", "--out", schemaDirectory.toString()), schemaWorkingDirectory, childEnvironment, launch)
          val schemaDrain = resources.scope.launch {
            schemaProcess.inputStream.use { stream ->
              val buffer = ByteArray(4096)
              while (stream.read(buffer) != -1) { }
            }
          }
          val schemaErrorDrain = resources.scope.launch {
            schemaProcess.errorStream.use { stream ->
              val buffer = ByteArray(4096)
              while (stream.read(buffer) != -1) { }
            }
          }
          awaitExit(schemaProcess)
          schemaDrain.join()
          schemaErrorDrain.join()
          if (schemaProcess.exitValue() != 0) throw CodexFailure(CodexFailureKind.PROTOCOL)
          val echoFields = CodexIsolation.validateSchema(schemaDirectory)
          val bootstrapPolicy = CodexIsolation()
          val bootstrapDirectory = resources.directory()
          val bootstrap = resources.client(resources.launch(listOf(binary.toString(), "app-server", "--listen", "stdio://") + bootstrapPolicy.arguments(), bootstrapDirectory, childEnvironment, launch))
          initialize(bootstrap)
          val discoveredPolicy = bootstrapPolicy.verify(readConfig(bootstrap))
          bootstrap.stop()
          val finalDirectory = resources.directory()
          val final = resources.client(resources.launch(listOf(binary.toString(), "app-server", "--listen", "stdio://") + discoveredPolicy.arguments(), finalDirectory, childEnvironment, launch))
          initialize(final)
          discoveredPolicy.verify(readConfig(final))
          CodexModelBackend(final, discoveredPolicy, finalDirectory, resources, echoFields)
        }
      } catch (e: CancellationException) {
        val primary = if (e is TimeoutCancellationException && currentCoroutineContext().isActive) CodexFailure(CodexFailureKind.STARTUP_TIMEOUT) else e
        try {
          resources.close()
        } catch (_: Exception) {
          primary.addSuppressed(CodexFailure(CodexFailureKind.CLEANUP))
        }
        throw primary
      } catch (e: Exception) {
        val primary = if (e is CodexFailure) e else CodexFailure(CodexFailureKind.PROTOCOL)
        try {
          resources.close()
        } catch (_: Exception) {
          primary.addSuppressed(CodexFailure(CodexFailureKind.CLEANUP))
        }
        throw primary
      }
    }

    private suspend fun initialize(client: CodexAppServerClient) {
      client.request(
        "initialize",
        buildJsonObject {
          put(
            "clientInfo",
            buildJsonObject {
              put("name", "verity")
              put("version", "1")
            },
          )
          put(
            "capabilities",
            buildJsonObject {
              put("experimentalApi", true)
              put("explicitGatewayOauth", true)
              put("requestAttestation", false)
            },
          )
        },
      )
      client.notify("initialized")
    }

    private suspend fun readConfig(client: CodexAppServerClient): JsonObject = client.request("config/read")["config"] as? JsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)

    private suspend fun awaitExit(process: Process) {
      while (process.isAlive) delay(10)
    }

    internal fun supportedVersion(value: String?): Boolean {
      val match = value?.let { Regex("codex-cli (\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(it) } ?: return false
      val numbers = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
      return numbers[0] > 0 || numbers[1] >= 159
    }

    private suspend fun findExecutable(): Path? = withContext(Dispatchers.IO) {
      System.getenv("PATH")?.split(java.io.File.pathSeparator)?.map { Path.of(it).resolve("codex") }?.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    private fun launchProcess(command: List<String>, directory: Path, environment: Map<String, String>): Process = ProcessBuilder(command).directory(directory.toFile()).apply {
      environment().clear()
      environment().putAll(environment)
    }.start()
  }
}
