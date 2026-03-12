package me.chrisbanes.verity.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
    ) {
      TODO("Implement open_session tool handler")
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
    ) {
      TODO("Implement close_session tool handler")
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
    ) {
      TODO("Implement list_journeys tool handler")
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
    ) {
      TODO("Implement load_journey tool handler")
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
    ) {
      TODO("Implement run_flow tool handler")
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
    ) {
      TODO("Implement press_key tool handler")
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
    ) {
      TODO("Implement capture_screenshot tool handler")
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
    ) {
      TODO("Implement capture_hierarchy tool handler")
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
    ) {
      TODO("Implement check_visible tool handler")
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
    ) {
      TODO("Implement check_focused tool handler")
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
            put("description", "Maestro YAML action to repeat each iteration")
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
    ) {
      TODO("Implement run_loop tool handler")
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
    ) {
      TODO("Implement get_context tool handler")
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
    TODO("Wire MCP SDK HTTP transport via Ktor — requires ktor-server-content-negotiation dependency")
  }
}
