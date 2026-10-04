package me.chrisbanes.verity.mcp

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.chrisbanes.verity.core.context.ContextBundle
import me.chrisbanes.verity.core.context.ContextLoader
import me.chrisbanes.verity.core.context.ContextStatus
import me.chrisbanes.verity.core.context.ContextValidationException
import me.chrisbanes.verity.core.hierarchy.FocusObservation
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PathPreflightChecker
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FocusCaptureResult
import me.chrisbanes.verity.device.FocusChangeObserver
import me.chrisbanes.verity.device.preflight.DevicePreflightChecker
import me.chrisbanes.verity.device.preflight.PlatformDevicePreflightChecker

class VerityMcpServer(
  private val sessionManager: McpDeviceSessionManager = McpDeviceSessionManager(),
  private val snapshotStore: McpHierarchySnapshotStore = McpHierarchySnapshotStore(),
  private val contextPath: File? = null,
  private val skipBundledContext: Boolean = false,
  private val devicePreflightChecker: DevicePreflightChecker = PlatformDevicePreflightChecker(),
  private val pathPreflightChecker: PathPreflightChecker = PathPreflightChecker(),
  private val requireContext: Boolean = false,
  private val defaultJourneysPath: File = File("."),
  private val defaultPlatform: Platform? = null,
  private val defaultDeviceId: String? = null,
  private val defaultDisableAnimations: Boolean = false,
  private val hierarchyDiffRenderer: suspend (UUID, ResolvedHierarchySnapshotPair) -> String = { sessionId, pair ->
    HierarchyDiff.render(sessionId, pair)
  },
  private val screenshotFileSaver: McpScreenshotFileSaver = McpScreenshotFileSaver(),
  private val focusObserver: FocusChangeObserver = FocusChangeObserver(),
) {

  fun create(): Server {
    val server = Server(
      serverInfo = Implementation(name = "verity", version = "0.1.0"),
      options = ServerOptions(
        capabilities = ServerCapabilities(
          tools = ServerCapabilities.Tools(),
        ),
      ),
    )

    registerOpenSession(server)
    registerCloseSession(server)
    registerListJourneys(server)
    registerLoadJourney(server)
    registerRunFlow(server)
    registerPressKey(server)
    registerCaptureScreenshot(server)
    registerCaptureHierarchy(server)
    registerCaptureFocusedTree(server)
    registerDiffHierarchy(server)
    registerCheckVisible(server)
    registerCheckFocused(server)
    registerRunLoop(server)
    registerGetContext(server)

    return server
  }

  // --- Arg helpers ---

  private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.content

  private fun JsonObject?.int(key: String): Int? = (this?.get(key) as? JsonPrimitive)?.intOrNull

  private fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull

  private fun JsonObject?.requireString(key: String): String = string(key) ?: throw IllegalArgumentException("Missing required parameter: $key")

  private fun success(text: String) = CallToolResult(
    content = listOf(TextContent(text = text)),
  )

  private val preflightJson = Json {
    prettyPrint = true
  }

  private fun preflightError(report: PreflightReport) = CallToolResult(
    content = listOf(TextContent(text = preflightJson.encodeToString(report))),
    isError = true,
  )

  private fun error(text: String) = CallToolResult(
    content = listOf(TextContent(text = text)),
    isError = true,
  )

  private fun Server.addSafeTool(
    name: String,
    description: String,
    inputSchema: ToolSchema,
    required: List<String>? = null,
    handler: suspend (JsonObject?) -> CallToolResult,
  ) {
    val schema = if (required != null) {
      ToolSchema(properties = inputSchema.properties, required = required)
    } else {
      inputSchema
    }
    addTool(name = name, description = description, inputSchema = schema) { request ->
      try {
        handler(request.params.arguments)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun parsePlatform(value: String): Platform = when (value) {
    "android-tv" -> Platform.ANDROID_TV
    "android" -> Platform.ANDROID_MOBILE
    "ios" -> Platform.IOS
    else -> throw IllegalArgumentException("Unknown platform: $value. Expected: android-tv, android, or ios")
  }

  // --- Tool Registrations ---

  private fun registerOpenSession(server: Server) {
    server.addSafeTool(
      name = "open_session",
      description = "Connect to a device and start a testing session",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("platform") {
            put("type", "string")
            put("description", "Target platform: android-tv, android, or ios")
            putJsonArray("enum") {
              add(JsonPrimitive("android-tv"))
              add(JsonPrimitive("android"))
              add(JsonPrimitive("ios"))
            }
          }
          putJsonObject("device") {
            put("type", "string")
            put("description", "Device serial or identifier (auto-discovers if omitted)")
          }
          putJsonObject("disable_animations") {
            put("type", "boolean")
            put("description", "Disable device animations for the session (Android only)")
          }
        },
      ),
    ) { args ->
      val platform = args.string("platform")?.let(::parsePlatform)
        ?: defaultPlatform
        ?: throw IllegalArgumentException(
          "Missing required parameter: platform. Provide platform or configure device.platform.",
        )
      val device = args.string("device") ?: defaultDeviceId
      val disableAnimations = args.bool("disable_animations") ?: defaultDisableAnimations
      val report = devicePreflightChecker.check(platform, device)
      if (!report.passed) return@addSafeTool preflightError(report)
      val handle = sessionManager.open(platform, device, disableAnimations)
      success(
        "Session opened.\nsession_id: ${handle.sessionId}\ndevice: ${handle.deviceId}\nplatform: $platform",
      )
    }
  }

  private fun registerCloseSession(server: Server) {
    server.addSafeTool(
      name = "close_session",
      description = "Close a device testing session and restore device state",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("session_id") {
            put("type", "string")
            put("description", "Session ID returned by open_session")
          }
        },
      ),
      required = listOf("session_id"),
    ) { args ->
      val sessionId = UUID.fromString(args.requireString("session_id"))
      sessionManager.close(sessionId)
      snapshotStore.clear(sessionId)
      success("Session $sessionId closed.")
    }
  }

  private fun registerListJourneys(server: Server) {
    server.addSafeTool(
      name = "list_journeys",
      description = "List available journey YAML files in a directory",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("path") {
            put("type", "string")
            put("description", "Directory to search for .journey.yaml files")
          }
        },
      ),
    ) { args ->
      val dir = args.string("path")?.let(::File) ?: defaultJourneysPath
      require(dir.isDirectory) {
        "Journey path must be a directory: ${dir.path}"
      }
      val files = withContext(Dispatchers.IO) {
        JourneyLoader.listJourneyFiles(dir)
      }
      if (files.isEmpty()) {
        success("No journey files found in: ${dir.absolutePath}")
      } else {
        success(files.joinToString("\n") { it.absolutePath })
      }
    }
  }

  private fun registerLoadJourney(server: Server) {
    server.addSafeTool(
      name = "load_journey",
      description = "Parse and display steps from a journey YAML file",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("path") {
            put("type", "string")
            put("description", "Path to the .journey.yaml file")
          }
        },
      ),
      required = listOf("path"),
    ) { args ->
      val path = args.requireString("path")
      val journey = withContext(Dispatchers.IO) {
        JourneyLoader.fromFile(File(path))
      }
      val output = buildString {
        appendLine("Journey: ${journey.name}")
        appendLine("App: ${journey.app}")
        appendLine("Platform: ${journey.platform}")
        appendLine()
        appendLine("Steps:")
        journey.steps.forEachIndexed { i, step ->
          when (step) {
            is JourneyStep.Action -> appendLine("  ${i + 1}. [Action] ${step.instruction}")
            is JourneyStep.Assert -> appendLine("  ${i + 1}. [Assert:${step.mode}] ${step.description}")
            is JourneyStep.Loop -> appendLine("  ${i + 1}. [Loop] ${step.action} until '${step.until}' (max: ${step.max})")
          }
        }
      }
      success(output.trim())
    }
  }

  private data class FocusWaitOptions(val await: Boolean, val timeoutMs: Int)

  private fun JsonObject?.focusWaitOptions(): FocusWaitOptions {
    val awaitValue = this?.get("await_focus_change")
    val await = if (awaitValue == null) {
      false
    } else {
      val primitive = awaitValue as? JsonPrimitive
      require(primitive != null && !primitive.isString && primitive.booleanOrNull != null) {
        "await_focus_change must be a JSON boolean"
      }
      primitive.booleanOrNull!!
    }
    if (!await) return FocusWaitOptions(false, 2000)
    val value = this?.get("focus_timeout_ms") ?: return FocusWaitOptions(true, 2000)
    val primitive = value as? JsonPrimitive
    val timeout = primitive?.takeUnless { it.isString }?.intOrNull
    require(timeout != null && timeout > 0 && primitive.content.matches(Regex("[0-9]+"))) {
      "focus_timeout_ms must be a positive JSON integer up to ${Int.MAX_VALUE}"
    }
    return FocusWaitOptions(true, timeout)
  }

  private fun kotlinx.serialization.json.JsonObjectBuilder.focusWaitSchema() {
    putJsonObject("await_focus_change") {
      put("type", "boolean")
      put("default", false)
      put("description", "Observe focus before the action and wait for a directly focused identity to change")
    }
    putJsonObject("focus_timeout_ms") {
      put("type", "integer")
      put("minimum", 1)
      put("maximum", Int.MAX_VALUE)
      put("default", 2000)
      put("description", "Post-action focus deadline in milliseconds; baseline has a separate equal capture budget")
    }
  }

  private data class FocusAction(val succeeded: Boolean, val output: String)

  private fun focusResult(
    actionStatus: String,
    output: String?,
    before: FocusObservation?,
    after: FocusObservation? = null,
    changed: Boolean = false,
    timedOut: Boolean = false,
    elapsedMs: Long = 0,
    errorCode: String? = null,
    errorMessage: String? = null,
  ): CallToolResult {
    fun observation(value: FocusObservation?) = value?.let {
      buildJsonArray {
        it.focusedNodes.forEach { node ->
          add(
            buildJsonObject {
              put("path", node.path)
              put("resource_id", node.resourceId?.let(::JsonPrimitive) ?: JsonNull)
            },
          )
        }
      }
    } ?: JsonNull
    val json = buildJsonObject {
      putJsonObject("action") {
        put("status", actionStatus)
        put("output", output?.let(::JsonPrimitive) ?: JsonNull)
      }
      put("focus_changed", changed)
      put("timed_out", timedOut)
      put("elapsed_ms", elapsedMs)
      put("focus_before", observation(before))
      put("focus_after", observation(after))
      put(
        "focus_error",
        if (errorCode == null) {
          JsonNull
        } else {
          buildJsonObject {
            put("code", errorCode)
            put("message", errorMessage ?: errorCode)
          }
        },
      )
    }
    return CallToolResult(content = listOf(TextContent(text = json.toString())), isError = actionStatus != "succeeded" || errorCode != null)
  }

  private suspend fun awaitFocusAction(
    session: DeviceSession,
    options: FocusWaitOptions,
    action: suspend () -> FocusAction,
  ): CallToolResult {
    val timeout = options.timeoutMs.milliseconds
    val before = when (val baseline = focusObserver.capture(session, timeout)) {
      is FocusCaptureResult.Captured -> baseline.observation
      FocusCaptureResult.TimedOut -> return focusResult("not_executed", null, null, errorCode = "baseline_capture_timed_out", errorMessage = "Baseline capture deadline expired")
      is FocusCaptureResult.Failed -> return focusResult("not_executed", null, null, errorCode = "baseline_capture_failed", errorMessage = baseline.cause.message ?: baseline.cause.toString())
    }
    val outcome = try {
      action().also { currentCoroutineContext().ensureActive() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      currentCoroutineContext().ensureActive()
      FocusAction(false, e.message ?: e.toString())
    }
    if (!outcome.succeeded) return focusResult("failed", outcome.output, before)
    val wait = focusObserver.awaitChange(session, before, timeout)
    return focusResult(
      "succeeded", outcome.output, before, wait.after, wait.changed, wait.timedOut, wait.elapsedMs,
      if (wait.captureError != null) "focus_capture_failed" else null,
      wait.captureError?.let { it.message ?: it.toString() },
    )
  }

  private fun registerRunFlow(server: Server) {
    server.addSafeTool(
      name = "run_flow",
      description = "Execute a Maestro YAML flow on the device, optionally waiting for focus to change",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("session_id") {
            put("type", "string")
            put("description", "Session ID returned by open_session")
          }
          putJsonObject("yaml") {
            put("type", "string")
            put("description", "Maestro YAML flow content to execute")
          }
          focusWaitSchema()
        },
      ),
      required = listOf("session_id", "yaml"),
    ) { args ->
      val options = args.focusWaitOptions()
      val sessionId = UUID.fromString(args.requireString("session_id"))
      val yaml = args.requireString("yaml")
      sessionManager.withSession(sessionId) { session ->
        if (options.await) {
          awaitFocusAction(session, options) {
            val result = session.executeFlow(yaml)
            FocusAction(result.success, result.output)
          }
        } else {
          val result = session.executeFlow(yaml)
          success(if (result.success) "SUCCESS" else "FAILED: ${result.output}")
        }
      }
    }
  }

  private fun registerPressKey(server: Server) {
    server.addSafeTool(
      name = "press_key",
      description = "Press a device key, optionally waiting for focus to change",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("session_id") {
            put("type", "string")
            put("description", "Session ID returned by open_session")
          }
          putJsonObject("key") {
            put("type", "string")
            put("description", "Key name to press (e.g., DPAD_UP, DPAD_CENTER, BACK)")
          }
          focusWaitSchema()
        },
      ),
      required = listOf("session_id", "key"),
    ) { args ->
      val options = args.focusWaitOptions()
      val sessionId = UUID.fromString(args.requireString("session_id"))
      val key = args.requireString("key")
      sessionManager.withSession(sessionId) { session ->
        if (options.await) {
          awaitFocusAction(session, options) {
            session.pressKey(key)
            FocusAction(true, "Pressed key: $key")
          }
        } else {
          session.pressKey(key)
          session.waitForAnimationToEnd()
          success("Pressed key: $key")
        }
      }
    }
  }

  private fun registerCaptureScreenshot(server: Server) {
    server.addSafeTool(
      name = "capture_screenshot",
      description = "Capture a screenshot from the device",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("session_id") {
            put("type", "string")
            put("description", "Session ID returned by open_session")
          }
          putJsonObject("save_to_file") {
            put("type", "string")
            put("description", "Optional PNG file path relative to the server working directory. Requires an existing writable parent and refuses existing destinations; returns the normalised absolute saved path.")
          }
        },
      ),
      required = listOf("session_id"),
    ) { args ->
      val sessionId = UUID.fromString(args.requireString("session_id"))
      val saveToFile = args.string("save_to_file")
      sessionManager.withSession(sessionId) { session ->
        if (saveToFile != null) {
          val target = Path.of(saveToFile).toAbsolutePath().normalize()
          val report = withContext(Dispatchers.IO) {
            pathPreflightChecker.requireWritableFileTarget(target, "Screenshot output")
          }
          if (!report.passed) return@withSession preflightError(report)
          val saved = try {
            screenshotFileSaver.save(target, session::captureScreenshot)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            val cleanupFailures = e.suppressed.filterIsInstance<ScreenshotCleanupException>()
            if (e !is ScreenshotCleanupException && cleanupFailures.isEmpty()) throw e
            return@withSession error(
              buildString {
                append("${e::class.simpleName}: ${e.message}")
                cleanupFailures.forEach { append("\n${it.message}") }
              },
            )
          }
          success("Screenshot saved to: $saved")
        } else {
          // Use a single cleanup block to ensure all temp files are deleted
          val tempFiles = mutableListOf<Path>()
          try {
            val tempPng = withContext(Dispatchers.IO) {
              Files.createTempFile("verity-screenshot-", ".png")
            }
            tempFiles.add(tempPng)

            session.captureScreenshot(tempPng)

            val jpegPath = withContext(Dispatchers.IO) {
              ScreenshotCompressor.compress(tempPng)
            }
            tempFiles.add(jpegPath)

            val bytes = withContext(Dispatchers.IO) {
              Files.readAllBytes(jpegPath)
            }
            val base64 = Base64.getEncoder().encodeToString(bytes)
            CallToolResult(
              content = listOf(ImageContent(data = base64, mimeType = "image/jpeg")),
            )
          } finally {
            withContext(NonCancellable + Dispatchers.IO) {
              tempFiles.forEach { Files.deleteIfExists(it) }
            }
          }
        }
      }
    }
  }

  private fun registerCaptureHierarchy(server: Server) {
    server.addSafeTool(
      name = "capture_hierarchy",
      description = "Capture the accessibility hierarchy from the device",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("session_id") {
            put("type", "string")
            put("description", "Session ID returned by open_session")
          }
          putJsonObject("filter") {
            put("type", "string")
            put("description", "Filter level: focus, content, or all")
            putJsonArray("enum") {
              add(JsonPrimitive("focus"))
              add(JsonPrimitive("content"))
              add(JsonPrimitive("all"))
            }
          }
        },
      ),
      required = listOf("session_id"),
    ) { args ->
      val sessionId = UUID.fromString(args.requireString("session_id"))
      val filter = when (args.string("filter")) {
        "focus" -> HierarchyFilter.FOCUS
        "all" -> HierarchyFilter.ALL
        else -> HierarchyFilter.CONTENT
      }
      sessionManager.withSession(sessionId) { session ->
        val tree = session.captureHierarchyTree()
        val snapshotId = snapshotStore.add(sessionId, tree)
        val rendered = HierarchyRenderer.render(tree, filter)
        success("snapshot_id: $snapshotId\n\n$rendered")
      }
    }
  }

  private fun registerCaptureFocusedTree(server: Server) {
    server.addSafeTool(
      name = "capture_focused_tree",
      description = "Capture bounded focused context (100 nodes, 12,000 UTF-16 units) with a full hierarchy snapshot",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("session_id") {
            put("type", "string")
            put("description", "Session ID returned by open_session")
          }
          putJsonObject("filter") {
            put("type", "string")
            put("description", "Filter level: focus, content (default), or all")
            putJsonArray("enum") {
              add(JsonPrimitive("focus"))
              add(JsonPrimitive("content"))
              add(JsonPrimitive("all"))
            }
          }
        },
      ),
      required = listOf("session_id"),
    ) { args ->
      val sessionId = UUID.fromString(args.requireString("session_id"))
      val filter = when (args.string("filter")) {
        "focus" -> HierarchyFilter.FOCUS
        "all" -> HierarchyFilter.ALL
        else -> HierarchyFilter.CONTENT
      }
      sessionManager.withSession(sessionId) { session ->
        val tree = session.captureHierarchyTree()
        val snapshotId = snapshotStore.add(sessionId, tree)
        success(McpFocusedTreeRenderer.render(tree, snapshotId.toString(), filter))
      }
    }
  }

  private class InvalidDiffArgument(val parameter: String) : IllegalArgumentException("Parameter $parameter must be a canonical UUID string.")

  private fun parseDiffId(args: JsonObject?, parameter: String, required: Boolean = false): UUID? {
    val value = args?.get(parameter) ?: if (required) throw InvalidDiffArgument(parameter) else return null
    val primitive = value as? JsonPrimitive ?: throw InvalidDiffArgument(parameter)
    if (!primitive.isString) throw InvalidDiffArgument(parameter)
    val id = try {
      UUID.fromString(primitive.content)
    } catch (_: IllegalArgumentException) {
      throw InvalidDiffArgument(parameter)
    }
    if (!id.toString().equals(primitive.content, ignoreCase = true)) throw InvalidDiffArgument(parameter)
    return id
  }

  private fun unavailableDiffSession(sessionId: UUID): CallToolResult = diffError(
    code = "session_unavailable",
    message = "Session $sessionId is unavailable or closed.",
    remediation = "Open a session and capture hierarchies before diffing.",
    sessionId = sessionId,
  )

  private fun diffError(
    code: String,
    message: String,
    remediation: String,
    sessionId: UUID? = null,
    parameter: String? = null,
    snapshotId: UUID? = null,
    requiredCaptures: Int? = null,
    availableCaptures: Int? = null,
  ): CallToolResult = error(
    buildJsonObject {
      putJsonObject("error") {
        put("code", code)
        put("message", message)
        put("remediation", remediation)
        sessionId?.let { put("session_id", it.toString()) }
        parameter?.let { put("parameter", it) }
        snapshotId?.let { put("snapshot_id", it.toString()) }
        requiredCaptures?.let { put("required_captures", it) }
        availableCaptures?.let { put("available_captures", it) }
      }
    }.toString(),
  )

  private fun registerDiffHierarchy(server: Server) {
    server.addSafeTool(
      name = "diff_hierarchy",
      description = "Compare two captured full hierarchy trees by child-index path without capturing the device",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          for ((name, description) in listOf(
            "session_id" to "Session ID returned by open_session",
            "before_snapshot_id" to "Snapshot from this session; defaults to its previous capture",
            "after_snapshot_id" to "Snapshot from this session; defaults to its latest capture",
          )) {
            putJsonObject(name) {
              put("type", "string")
              put("description", description)
            }
          }
        },
      ),
      required = listOf("session_id"),
    ) { args ->
      val sessionId: UUID
      val beforeId: UUID?
      val afterId: UUID?
      try {
        sessionId = parseDiffId(args, "session_id", required = true)!!
        beforeId = parseDiffId(args, "before_snapshot_id")
        afterId = parseDiffId(args, "after_snapshot_id")
      } catch (e: InvalidDiffArgument) {
        return@addSafeTool diffError(
          code = "invalid_argument",
          message = e.message!!,
          remediation = "Supply canonical UUID strings. Omit optional snapshot IDs to use capture defaults.",
          parameter = e.parameter,
        )
      }
      var callbackEntered = false
      try {
        sessionManager.withSession(sessionId) {
          callbackEntered = true
          if (!sessionManager.isOpen(sessionId)) return@withSession unavailableDiffSession(sessionId)
          when (val pair = snapshotStore.resolvePair(sessionId, beforeId, afterId)) {
            is ResolvedHierarchySnapshotPair -> success(hierarchyDiffRenderer(sessionId, pair))

            is UnavailableHierarchySnapshot -> diffError(
              code = "snapshot_unavailable",
              message = "Snapshot ${pair.snapshotId} is missing, evicted, or belongs to another session.",
              remediation = "Capture again or use a snapshot ID from this session.",
              sessionId = pair.sessionId,
              parameter = pair.parameter,
              snapshotId = pair.snapshotId,
            )

            is InsufficientHierarchyCaptures -> diffError(
              code = "insufficient_captures",
              message = "Resolving ${pair.parameter} requires ${pair.requiredCaptures} captures; this session has ${pair.availableCaptures}.",
              remediation = "Capture another hierarchy before using this default.",
              sessionId = pair.sessionId,
              parameter = pair.parameter,
              requiredCaptures = pair.requiredCaptures,
              availableCaptures = pair.availableCaptures,
            )
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: IllegalArgumentException) {
        if (callbackEntered) throw e
        unavailableDiffSession(sessionId)
      }
    }
  }

  private fun registerCheckVisible(server: Server) {
    server.addSafeTool(
      name = "check_visible",
      description = "Check if text is visible on screen (deterministic substring match)",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("session_id") {
            put("type", "string")
            put("description", "Session ID returned by open_session")
          }
          putJsonObject("text") {
            put("type", "string")
            put("description", "Text to search for (case-insensitive)")
          }
        },
      ),
      required = listOf("session_id", "text"),
    ) { args ->
      val sessionId = UUID.fromString(args.requireString("session_id"))
      val text = args.requireString("text")
      val visible = sessionManager.withSession(sessionId) { session ->
        session.containsText(text)
      }
      success(if (visible) "true" else "false")
    }
  }

  private fun registerCheckFocused(server: Server) {
    server.addSafeTool(
      name = "check_focused",
      description = "Check if an element containing the given text is focused",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("session_id") {
            put("type", "string")
            put("description", "Session ID returned by open_session")
          }
          putJsonObject("text") {
            put("type", "string")
            put("description", "Text of the element to check focus on")
          }
        },
      ),
      required = listOf("session_id", "text"),
    ) { args ->
      val sessionId = UUID.fromString(args.requireString("session_id"))
      val text = args.requireString("text")
      val focused = sessionManager.withSession(sessionId) { session ->
        session.checkFocused(text)
      }
      success(if (focused) "true" else "false")
    }
  }

  private fun registerRunLoop(server: Server) {
    server.addSafeTool(
      name = "run_loop",
      description = "Repeat an action until a condition is met or max iterations reached",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("session_id") {
            put("type", "string")
            put("description", "Session ID returned by open_session")
          }
          putJsonObject("action") {
            put("type", "string")
            put("description", "Key name to press each iteration (e.g., DPAD_DOWN)")
          }
          putJsonObject("until") {
            put("type", "string")
            put("description", "Text to check for — loop stops when visible")
          }
          putJsonObject("max") {
            put("type", "integer")
            put("description", "Maximum iterations (default: 10)")
          }
          putJsonObject("wait_ms") {
            put("type", "integer")
            put("description", "Milliseconds to wait between iterations (default: 500)")
          }
        },
      ),
      required = listOf("session_id", "action", "until"),
    ) { args ->
      val sessionId = UUID.fromString(args.requireString("session_id"))
      val action = args.requireString("action")
      val until = args.requireString("until")
      val max = args.int("max") ?: 10
      val waitMs = args.int("wait_ms") ?: 500
      val result = sessionManager.withSession(sessionId) { session ->
        var actionsExecuted = 0
        repeat(max) {
          if (session.containsText(until)) {
            return@withSession "SATISFIED after $actionsExecuted iterations: text '$until' found"
          }
          session.pressKey(action)
          session.waitForAnimationToEnd()
          if (waitMs > 0) delay(waitMs.toLong())
          actionsExecuted += 1
        }
        if (session.containsText(until)) {
          "SATISFIED after $actionsExecuted iterations: text '$until' found"
        } else {
          "NOT SATISFIED after $actionsExecuted iterations: text '$until' not found"
        }
      }
      success(result)
    }
  }

  private fun registerGetContext(server: Server) {
    server.addSafeTool(
      name = "get_context",
      description = "Load context for prompt injection. Returns bundled Maestro and TV controls defaults, optionally augmented with app-specific markdown files from a directory path.",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("path") {
            put("type", "string")
            put("description", "Directory containing context markdown files")
          }
        },
      ),
    ) { args ->
      val pathArg = args.string("path")
      val contextDir = pathArg?.let { File(it) } ?: contextPath
      val projectContext = try {
        withContext(Dispatchers.IO) {
          ContextLoader.loadProject(directory = contextDir, required = requireContext)
        }
      } catch (e: ContextValidationException) {
        return@addSafeTool error(e.message ?: "Project context validation failed")
      }
      val bundled = if (skipBundledContext) "" else ContextLoader.loadBundled()
      val metadata = projectContext.describeForMcp(contextDir, requireContext)
      val usableContext = listOf(bundled, projectContext.text)
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

      if (usableContext.isBlank()) {
        error("No context path configured and no bundled defaults found.")
      } else {
        val content = listOf(metadata, usableContext)
          .filter { it.isNotBlank() }
          .joinToString("\n\n")
        success(content)
      }
    }
  }

  private fun ContextBundle.describeForMcp(contextDir: File?, required: Boolean): String {
    val mode = if (required) "required" else "optional"
    return when (status) {
      ContextStatus.LOADED -> buildString {
        appendLine("Project context: loaded ${loadedFiles.size} file(s)")
        loadedFiles.forEach { appendLine("- ${it.absolutePath}") }
      }.trim()

      ContextStatus.NOT_CONFIGURED -> "Project context: $mode, not configured"

      ContextStatus.MISSING_DIRECTORY ->
        "Project context: $mode, missing directory: ${contextDir!!.absolutePath}"

      ContextStatus.EMPTY_DIRECTORY ->
        "Project context: $mode, no markdown files found in: ${contextDir!!.absolutePath}"
    }
  }

  // --- Transport ---

  suspend fun startStdio() {
    val server = create()
    val transport = StdioServerTransport(
      System.`in`.asSource().buffered(),
      System.out.asSink().buffered(),
    ) {}
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
  }

  suspend fun startHttp(host: String = "0.0.0.0", port: Int = 8080) {
    embeddedServer(Netty, host = host, port = port) {
      mcpStreamableHttp(path = "/mcp") {
        this@VerityMcpServer.create()
      }
    }.start(wait = true)
  }
}
