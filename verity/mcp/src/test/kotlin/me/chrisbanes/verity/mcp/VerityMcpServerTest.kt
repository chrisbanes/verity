package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isIn
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.core.preflight.PreflightIssue
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.core.preflight.PreflightSeverity
import me.chrisbanes.verity.device.FakeDeviceSession
import me.chrisbanes.verity.device.preflight.DevicePreflightChecker

class VerityMcpServerTest {

  @Test
  fun `server creates successfully`() {
    val server = VerityMcpServer().create()
    assertThat(server).isNotNull()
  }

  @Test
  fun `server registers all 12 tools`() {
    val server = VerityMcpServer().create()
    assertThat(server.tools.keys).containsExactlyInAnyOrder(
      "open_session",
      "close_session",
      "list_journeys",
      "load_journey",
      "run_flow",
      "press_key",
      "capture_screenshot",
      "capture_hierarchy",
      "check_visible",
      "check_focused",
      "run_loop",
      "get_context",
    )
  }

  @Test
  fun `each tool has a description`() {
    val server = VerityMcpServer().create()
    for ((name, tool) in server.tools) {
      assertThat(tool.tool.description, name = "description of tool '$name'")
        .isNotNull().isNotEmpty()
    }
  }

  @Test
  fun `tool count is exactly 12`() {
    val server = VerityMcpServer().create()
    assertThat(server.tools.size).isEqualTo(12)
  }

  @Test
  fun `get_context returns bundled defaults when no path configured`() = runTest {
    val server = VerityMcpServer().create()
    val tool = server.tools["get_context"]!!
    val request = CallToolRequest(CallToolRequestParams(name = "get_context"))
    val result = tool.handler.invoke(StubClientConnection(), request)
    val text = (result.content.first() as TextContent).text
    assertThat(result.isError).isIn(null, false)
    assertThat(text).contains("Maestro")
    assertThat(text).contains("Remote Dpad")
  }

  @Test
  fun `open_session returns structured preflight error and does not open session`() = runTest {
    var sessionFactoryCalled = false
    val server = VerityMcpServer(
      sessionManager = McpDeviceSessionManager { _, _, _ ->
        sessionFactoryCalled = true
        error("session factory should not be called")
      },
      devicePreflightChecker = DevicePreflightChecker { _, _ ->
        PreflightReport(
          listOf(
            PreflightIssue(
              code = PreflightCodes.ANDROID_DEVICE_MISSING,
              severity = PreflightSeverity.ERROR,
              message = "No Android device was found.",
              remediation = "Start an emulator.",
            ),
          ),
        )
      },
    ).create()

    val request = CallToolRequest(
      CallToolRequestParams(
        name = "open_session",
        arguments = buildJsonObject {
          put("platform", JsonPrimitive("android"))
        },
      ),
    )

    val result = server.tools["open_session"]!!.handler.invoke(StubClientConnection(), request)
    val text = (result.content.first() as TextContent).text

    assertThat(result.isError).isEqualTo(true)
    assertThat(text).contains("android.device.missing")
    assertThat(text).contains("No Android device was found")
    assertThat(sessionFactoryCalled).isFalse()
  }

  @Test
  fun `capture_screenshot returns preflight error for unwritable save target`() = runTest {
    val session = FakeDeviceSession()
    val manager = McpDeviceSessionManager { _, _, _ -> session }
    val handle = manager.open(Platform.ANDROID_MOBILE, "device")
    val server = VerityMcpServer(
      sessionManager = manager,
      devicePreflightChecker = DevicePreflightChecker { _, _ ->
        PreflightReport()
      },
    ).create()

    val request = CallToolRequest(
      CallToolRequestParams(
        name = "capture_screenshot",
        arguments = buildJsonObject {
          put("session_id", JsonPrimitive(handle.sessionId.toString()))
          put("save_to_file", JsonPrimitive("/missing-parent/screenshot.png"))
        },
      ),
    )

    val result = server.tools["capture_screenshot"]!!.handler.invoke(StubClientConnection(), request)
    val text = (result.content.first() as TextContent).text

    assertThat(result.isError).isEqualTo(true)
    assertThat(text).contains("path.not_writable")
  }
}
