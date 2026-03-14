package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

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
}
