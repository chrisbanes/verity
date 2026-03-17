package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isTrue
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.device.FakeDeviceSession

class VerityMcpServerSmokeTest {

  private fun createSmokeServer(): Pair<VerityMcpServer, FakeDeviceSession> {
    val fakeSession = FakeDeviceSession(
      hierarchyNode = HierarchyNode(
        attributes = mapOf("class" to "FrameLayout"),
        children = listOf(
          HierarchyNode(attributes = mapOf("text" to "Home", "focused" to "true")),
          HierarchyNode(attributes = mapOf("text" to "Settings")),
        ),
      ),
    )
    val sessionManager = McpDeviceSessionManager(
      sessionFactory = { _, _, _ -> fakeSession },
    )
    val server = VerityMcpServer(sessionManager = sessionManager)
    return server to fakeSession
  }

  private fun textOf(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String = (result.content.first() as TextContent).text

  private fun callRequest(name: String, args: kotlinx.serialization.json.JsonObject? = null) = CallToolRequest(CallToolRequestParams(name = name, arguments = args))

  @Test
  fun `open and close session lifecycle`() = runTest {
    val (mcpServer, fakeSession) = createSmokeServer()
    val server = mcpServer.create()
    val conn = StubClientConnection()

    // Open session
    val openResult = server.tools["open_session"]!!.handler.invoke(
      conn,
      callRequest(
        "open_session",
        buildJsonObject {
          put("platform", "android-tv")
        },
      ),
    )
    assertThat(openResult.isError).isIn(null, false)
    val openText = textOf(openResult)
    assertThat(openText).contains("Session opened")
    assertThat(openText).contains("session_id:")

    // Extract session_id
    val sessionId = openText.lines()
      .first { it.startsWith("session_id:") }
      .substringAfter("session_id:").trim()

    // Close session
    val closeResult = server.tools["close_session"]!!.handler.invoke(
      conn,
      callRequest(
        "close_session",
        buildJsonObject {
          put("session_id", sessionId)
        },
      ),
    )
    assertThat(closeResult.isError).isIn(null, false)
    assertThat(textOf(closeResult)).contains("closed")
    assertThat(fakeSession.closed).isTrue()
  }

  @Test
  fun `check_visible is case-insensitive`() = runTest {
    val (mcpServer, _) = createSmokeServer()
    val server = mcpServer.create()
    val conn = StubClientConnection()

    // Open session first
    val openText = textOf(
      server.tools["open_session"]!!.handler.invoke(
        conn,
        callRequest("open_session", buildJsonObject { put("platform", "android-tv") }),
      ),
    )
    val sessionId = openText.lines().first { it.startsWith("session_id:") }.substringAfter("session_id:").trim()

    // check_visible with exact case
    val exactResult = server.tools["check_visible"]!!.handler.invoke(
      conn,
      callRequest(
        "check_visible",
        buildJsonObject {
          put("session_id", sessionId)
          put("text", "Home")
        },
      ),
    )
    assertThat(textOf(exactResult)).isEqualTo("true")

    // check_visible with different case (should still match — case-insensitive)
    val caseResult = server.tools["check_visible"]!!.handler.invoke(
      conn,
      callRequest(
        "check_visible",
        buildJsonObject {
          put("session_id", sessionId)
          put("text", "home")
        },
      ),
    )
    assertThat(textOf(caseResult)).isEqualTo("true")

    // check_visible with text not present
    val missingResult = server.tools["check_visible"]!!.handler.invoke(
      conn,
      callRequest(
        "check_visible",
        buildJsonObject {
          put("session_id", sessionId)
          put("text", "NotPresent")
        },
      ),
    )
    assertThat(textOf(missingResult)).isEqualTo("false")
  }

  @Test
  fun `capture_hierarchy returns snapshot and rendered tree`() = runTest {
    val (mcpServer, _) = createSmokeServer()
    val server = mcpServer.create()
    val conn = StubClientConnection()

    // Open session
    val openText = textOf(
      server.tools["open_session"]!!.handler.invoke(
        conn,
        callRequest("open_session", buildJsonObject { put("platform", "android-tv") }),
      ),
    )
    val sessionId = openText.lines().first { it.startsWith("session_id:") }.substringAfter("session_id:").trim()

    // Capture hierarchy
    val hierResult = server.tools["capture_hierarchy"]!!.handler.invoke(
      conn,
      callRequest(
        "capture_hierarchy",
        buildJsonObject {
          put("session_id", sessionId)
          put("filter", "content")
        },
      ),
    )
    assertThat(hierResult.isError).isIn(null, false)
    val hierText = textOf(hierResult)
    assertThat(hierText).contains("snapshot_id:")
    // The rendered tree should contain text from our fake hierarchy
    assertThat(hierText).contains("Home")
    assertThat(hierText).contains("Settings")
  }
}
