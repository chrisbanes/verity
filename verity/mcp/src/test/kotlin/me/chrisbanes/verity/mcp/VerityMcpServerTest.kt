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
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.core.preflight.PreflightIssue
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.core.preflight.PreflightSeverity
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession
import me.chrisbanes.verity.device.preflight.DevicePreflightChecker

class VerityMcpServerTest {

  @Test
  fun `server creates successfully`() {
    val server = VerityMcpServer().create()
    assertThat(server).isNotNull()
  }

  @Test
  fun `server registers all 14 tools`() {
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
      "capture_focused_tree",
      "diff_hierarchy",
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
  fun `tool count is exactly 14`() {
    val server = VerityMcpServer().create()
    assertThat(server.tools.size).isEqualTo(14)
  }

  @Test
  fun `get_context returns bundled defaults when no path configured`() = runTest {
    val server = VerityMcpServer().create()
    val tool = server.tools["get_context"]!!
    val request = CallToolRequest(CallToolRequestParams(name = "get_context"))
    val result = tool.handler.invoke(StubClientConnection(), request)
    val text = (result.content.first() as TextContent).text
    assertThat(result.isError).isIn(null, false)
    assertThat(text).contains("Maestro YAML Reference")
    assertThat(text.contains("Structured action reference")).isFalse()
    assertThat(text).contains("Remote Dpad")
  }

  @Test
  fun `run_flow forwards exact supplied edited and invalid YAML without typed execution`() = runTest {
    val supplied = "appId: com.example\n---\n- tapOn: Settings\n"
    val edited = "# manually edited\nappId: com.example\n---\n- tapOn:\n    text: Account\n- waitForAnimationToEnd\n"
    val invalid = "appId: com.example\n---\n- tapOn: [unfinished"
    for (suppliedYaml in listOf(supplied, edited, invalid)) {
      for (awaitFocus in listOf(false, true)) {
        val events = mutableListOf<String>()
        val fake = FakeDeviceSession()
        val session = object : DeviceSession by fake {
          override suspend fun executeActions(flow: ActionFlow): FlowResult = error("MCP must retain supplied YAML")
          override suspend fun executeFlow(yaml: String): FlowResult {
            assertThat(yaml).isEqualTo(suppliedYaml)
            fake.executedFlows += yaml
            events += "flow"
            return FlowResult(yaml != invalid, "syntax failure")
          }
          override suspend fun waitForAnimationToEnd() {
            events += "animation"
          }
        }
        val manager = McpDeviceSessionManager { _, _, _ -> session }
        val handle = manager.open(Platform.ANDROID_TV, "fixture")
        try {
          val server = VerityMcpServer(sessionManager = manager).create()
          val result = server.tools.getValue("run_flow").handler.invoke(
            StubClientConnection(),
            CallToolRequest(
              CallToolRequestParams(
                "run_flow",
                arguments = buildJsonObject {
                  put("session_id", handle.sessionId.toString())
                  put("yaml", suppliedYaml)
                  if (awaitFocus) put("await_focus_change", true)
                },
              ),
            ),
          )
          assertThat(result.isError).isIn(null, false)
          assertThat((result.content.single() as TextContent).text).isEqualTo(if (suppliedYaml == invalid) "FAILED: syntax failure" else "SUCCESS")
          assertThat(fake.executedFlows).isEqualTo(listOf(suppliedYaml))
          assertThat(events).isEqualTo(if (awaitFocus) listOf("flow", "animation") else listOf("flow"))
        } finally {
          manager.close(handle.sessionId)
        }
        assertThat(fake.closed).isEqualTo(true)
      }
    }
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

  @Test
  fun `get_context reports optional missing configured path`() = runTest {
    val server = VerityMcpServer(contextPath = File("/nonexistent/context")).create()
    val tool = server.tools["get_context"]!!

    val result = tool.handler.invoke(
      StubClientConnection(),
      CallToolRequest(CallToolRequestParams(name = "get_context")),
    )

    val text = (result.content.first() as TextContent).text
    assertThat(result.isError).isIn(null, false)
    assertThat(text).contains("Project context: optional, missing directory: /nonexistent/context")
    assertThat(text).contains("Maestro")
  }

  @Test
  fun `get_context errors when bundled and project context are absent`() = runTest {
    val server = VerityMcpServer(skipBundledContext = true).create()
    val tool = server.tools["get_context"]!!

    val result = tool.handler.invoke(
      StubClientConnection(),
      CallToolRequest(CallToolRequestParams(name = "get_context")),
    )

    val text = (result.content.first() as TextContent).text
    assertThat(result.isError).isEqualTo(true)
    assertThat(text).contains("No context path configured and no bundled defaults found.")
  }

  @Test
  fun `get_context reports loaded project context files`() = runTest {
    val dir = kotlin.io.path.createTempDirectory("mcp-context").toFile()
    try {
      File(dir, "app.md").writeText("# App context")
      val server = VerityMcpServer(contextPath = dir).create()
      val tool = server.tools["get_context"]!!

      val result = tool.handler.invoke(
        StubClientConnection(),
        CallToolRequest(CallToolRequestParams(name = "get_context")),
      )

      val text = (result.content.first() as TextContent).text
      assertThat(result.isError).isIn(null, false)
      assertThat(text).contains("Project context: loaded 1 file(s)")
      assertThat(text).contains("app.md")
      assertThat(text).contains("# App context")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `get_context errors when required configured path is missing`() = runTest {
    val server = VerityMcpServer(
      contextPath = File("/nonexistent/context"),
      requireContext = true,
    ).create()
    val tool = server.tools["get_context"]!!

    val result = tool.handler.invoke(
      StubClientConnection(),
      CallToolRequest(CallToolRequestParams(name = "get_context")),
    )

    val text = (result.content.first() as TextContent).text
    assertThat(result.isError).isEqualTo(true)
    assertThat(text)
      .contains("Required project context directory does not exist or is not a directory: /nonexistent/context")
  }

  @Test
  fun `list journeys uses configured default directory when path argument omitted`() = runTest {
    val journeysDir = kotlin.io.path.createTempDirectory("verity-journeys").toFile()
    journeysDir.resolve("sample.journey.yaml").writeText(
      """
      name: Sample
      app: com.example
      platform: android-tv
      steps:
        - "[?] Home"
      """.trimIndent(),
    )

    val server = VerityMcpServer(defaultJourneysPath = journeysDir).create()
    val result = server.tools["list_journeys"]!!.handler.invoke(
      StubClientConnection(),
      CallToolRequest(CallToolRequestParams(name = "list_journeys")),
    )

    val text = (result.content.first() as TextContent).text
    assertThat(result.isError).isIn(null, false)
    assertThat(text).contains("sample.journey.yaml")
  }

  @Test
  fun `open session uses configured defaults when tool args are omitted`() = runTest {
    var capturedPlatform: Platform? = null
    var capturedDeviceId: String? = null
    var capturedDisableAnimations: Boolean? = null
    val fakeSession = FakeDeviceSession()
    val sessionManager = McpDeviceSessionManager(
      sessionFactory = { platform, deviceId, disableAnimations ->
        capturedPlatform = platform
        capturedDeviceId = deviceId
        capturedDisableAnimations = disableAnimations
        fakeSession
      },
    )
    val server = VerityMcpServer(
      sessionManager = sessionManager,
      devicePreflightChecker = DevicePreflightChecker { _, _ ->
        PreflightReport()
      },
      defaultPlatform = Platform.ANDROID_TV,
      defaultDeviceId = "configured-device",
      defaultDisableAnimations = true,
    ).create()

    val result = server.tools["open_session"]!!.handler.invoke(
      StubClientConnection(),
      CallToolRequest(CallToolRequestParams(name = "open_session")),
    )

    assertThat(result.isError).isIn(null, false)
    assertThat(capturedPlatform).isEqualTo(Platform.ANDROID_TV)
    assertThat(capturedDeviceId).isEqualTo("configured-device")
    assertThat(capturedDisableAnimations).isEqualTo(true)
  }

  @Test
  fun `run_loop retains literal condition and raw key behavior alongside deterministic tools`() = runTest {
    val session = FakeDeviceSession(hierarchyNode = HierarchyNode(attributes = mapOf("text" to "Settings"), states = setOf("focused")))
    val manager = McpDeviceSessionManager { _, _, _ -> session }
    val handle = manager.open(Platform.ANDROID_TV, "fake")
    val server = VerityMcpServer(sessionManager = manager).create()
    suspend fun call(name: String, text: String): String {
      val args = buildJsonObject {
        put("session_id", handle.sessionId.toString())
        if (name == "run_loop") {
          put("action", "DPAD_DOWN")
          put("until", text)
          put("max", 2)
          put("wait_ms", 0)
        } else {
          put("text", text)
        }
      }
      val result = server.tools[name]!!.handler.invoke(StubClientConnection(), CallToolRequest(CallToolRequestParams(name, arguments = args)))
      assertThat(result.isError).isIn(null, false)
      return (result.content.single() as TextContent).text
    }
    assertThat(call("check_visible", "Settings")).isEqualTo("true")
    assertThat(call("check_focused", "Settings")).isEqualTo("true")
    assertThat(call("run_loop", "Settings")).isEqualTo("SATISFIED after 0 iterations: text 'Settings' found")
    assertThat(call("run_loop", "Settings is focused")).isEqualTo("NOT SATISFIED after 2 iterations: text 'Settings is focused' not found")
    assertThat(session.pressedKeys).isEqualTo(listOf("DPAD_DOWN", "DPAD_DOWN"))
    assertThat(server.tools["run_loop"]!!.tool.inputSchema.properties!!.keys).containsExactlyInAnyOrder("session_id", "action", "until", "max", "wait_ms")
  }
}
