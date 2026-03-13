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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.chrisbanes.verity.core.context.ContextLoader
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform

class VerityMcpServer(
  private val sessionManager: McpDeviceSessionManager = McpDeviceSessionManager(),
  private val snapshotStore: McpHierarchySnapshotStore = McpHierarchySnapshotStore(),
  private val contextPath: File? = null,
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

  private fun error(text: String) = CallToolResult(
    content = listOf(TextContent(text = text)),
    isError = true,
  )

  private fun parsePlatform(value: String): Platform = when (value) {
    "android-tv" -> Platform.ANDROID_TV
    "android" -> Platform.ANDROID_MOBILE
    "ios" -> Platform.IOS
    else -> throw IllegalArgumentException("Unknown platform: $value. Expected: android-tv, android, or ios")
  }

  // --- Tool Registrations ---

  private fun registerOpenSession(server: Server) {
    server.addTool(
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
    ) { request ->
      val args = request.params.arguments
      try {
        val platform = parsePlatform(args.requireString("platform"))
        val device = args.string("device")
        val disableAnimations = args.bool("disable_animations") ?: false
        val handle = sessionManager.open(platform, device, disableAnimations)
        success(
          "Session opened.\nsession_id: ${handle.sessionId}\ndevice: ${handle.deviceId}\nplatform: $platform",
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun registerCloseSession(server: Server) {
    server.addTool(
      name = "close_session",
      description = "Close a device testing session and restore device state",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("session_id") {
            put("type", "string")
            put("description", "Session ID returned by open_session")
          }
        },
        required = listOf("session_id"),
      ),
    ) { request ->
      val args = request.params.arguments
      try {
        val sessionId = UUID.fromString(args.requireString("session_id"))
        sessionManager.close(sessionId)
        snapshotStore.clear(sessionId)
        success("Session $sessionId closed.")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun registerListJourneys(server: Server) {
    server.addTool(
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
    ) { request ->
      val args = request.params.arguments
      try {
        val dir = File(args.string("path") ?: ".")
        val files = withContext(Dispatchers.IO) {
          JourneyLoader.listJourneyFiles(dir)
        }
        if (files.isEmpty()) {
          success("No journey files found in: ${dir.absolutePath}")
        } else {
          success(files.joinToString("\n") { it.absolutePath })
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun registerLoadJourney(server: Server) {
    server.addTool(
      name = "load_journey",
      description = "Parse and display steps from a journey YAML file",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("path") {
            put("type", "string")
            put("description", "Path to the .journey.yaml file")
          }
        },
        required = listOf("path"),
      ),
    ) { request ->
      val args = request.params.arguments
      try {
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
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun registerRunFlow(server: Server) {
    server.addTool(
      name = "run_flow",
      description = "Execute a Maestro YAML flow on the device",
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
          putJsonObject("await_focus_change") {
            put("type", "boolean")
            put("description", "Wait for focus to change after execution (Android TV)")
          }
        },
        required = listOf("session_id", "yaml"),
      ),
    ) { request ->
      val args = request.params.arguments
      try {
        val sessionId = UUID.fromString(args.requireString("session_id"))
        val yaml = args.requireString("yaml")
        val awaitFocusChange = args.bool("await_focus_change") ?: false
        val result = sessionManager.withSession(sessionId) { session ->
          val flowResult = session.executeFlow(yaml)
          if (awaitFocusChange) session.waitForAnimationToEnd()
          flowResult
        }
        if (result.success) {
          success("SUCCESS")
        } else {
          success("FAILED: ${result.output}")
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun registerPressKey(server: Server) {
    server.addTool(
      name = "press_key",
      description = "Press a key on the device (e.g., DPAD_CENTER, BACK, HOME)",
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
        },
        required = listOf("session_id", "key"),
      ),
    ) { request ->
      val args = request.params.arguments
      try {
        val sessionId = UUID.fromString(args.requireString("session_id"))
        val key = args.requireString("key")
        sessionManager.withSession(sessionId) { session ->
          session.pressKey(key)
          session.waitForAnimationToEnd()
        }
        success("Pressed key: $key")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun registerCaptureScreenshot(server: Server) {
    server.addTool(
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
            put("description", "Optional file path to save the screenshot PNG")
          }
        },
        required = listOf("session_id"),
      ),
    ) { request ->
      val args = request.params.arguments
      try {
        val sessionId = UUID.fromString(args.requireString("session_id"))
        val saveToFile = args.string("save_to_file")
        sessionManager.withSession(sessionId) { session ->
          if (saveToFile != null) {
            val target = Path.of(saveToFile)
            session.captureScreenshot(target)
            success("Screenshot saved to: $saveToFile")
          } else {
            val tempPng = withContext(Dispatchers.IO) {
              Files.createTempFile("verity-screenshot-", ".png")
            }
            try {
              session.captureScreenshot(tempPng)
              val jpegPath = withContext(Dispatchers.IO) {
                ScreenshotCompressor.compress(tempPng)
              }
              try {
                val bytes = withContext(Dispatchers.IO) {
                  Files.readAllBytes(jpegPath)
                }
                val base64 = Base64.getEncoder().encodeToString(bytes)
                CallToolResult(
                  content = listOf(ImageContent(data = base64, mimeType = "image/jpeg")),
                )
              } finally {
                withContext(Dispatchers.IO) {
                  Files.deleteIfExists(jpegPath)
                }
              }
            } finally {
              withContext(Dispatchers.IO) {
                Files.deleteIfExists(tempPng)
              }
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun registerCaptureHierarchy(server: Server) {
    server.addTool(
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
        required = listOf("session_id"),
      ),
    ) { request ->
      val args = request.params.arguments
      try {
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
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun registerCheckVisible(server: Server) {
    server.addTool(
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
        required = listOf("session_id", "text"),
      ),
    ) { request ->
      val args = request.params.arguments
      try {
        val sessionId = UUID.fromString(args.requireString("session_id"))
        val text = args.requireString("text")
        val visible = sessionManager.withSession(sessionId) { session ->
          session.containsText(text)
        }
        success(if (visible) "true" else "false")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun registerCheckFocused(server: Server) {
    server.addTool(
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
        required = listOf("session_id", "text"),
      ),
    ) { request ->
      val args = request.params.arguments
      try {
        val sessionId = UUID.fromString(args.requireString("session_id"))
        val text = args.requireString("text")
        val focused = sessionManager.withSession(sessionId) { session ->
          session.checkFocused(text)
        }
        success(if (focused) "true" else "false")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun registerRunLoop(server: Server) {
    server.addTool(
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
        required = listOf("session_id", "action", "until"),
      ),
    ) { request ->
      val args = request.params.arguments
      try {
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
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  private fun registerGetContext(server: Server) {
    server.addTool(
      name = "get_context",
      description = "Load app-specific context from markdown files for prompt injection",
      inputSchema = ToolSchema(
        properties = buildJsonObject {
          putJsonObject("path") {
            put("type", "string")
            put("description", "Directory containing context markdown files")
          }
        },
      ),
    ) { request ->
      val args = request.params.arguments
      try {
        val pathArg = args.string("path")
        val contextDir = when {
          pathArg != null -> File(pathArg)

          contextPath != null -> contextPath

          else -> return@addTool error(
            "No context path configured. Use the 'path' parameter or start the server with --context-path.",
          )
        }
        val context = withContext(Dispatchers.IO) {
          ContextLoader.load(contextDir)
        }
        if (context.isBlank()) {
          success("No context files found in: ${contextDir.absolutePath}")
        } else {
          success(context)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        error("${e::class.simpleName}: ${e.message}")
      }
    }
  }

  // --- Transport ---

  suspend fun startStdio() {
    val server = create()
    val transport = StdioServerTransport(
      System.`in`.asSource().buffered(),
      System.out.asSink().buffered(),
    )
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
