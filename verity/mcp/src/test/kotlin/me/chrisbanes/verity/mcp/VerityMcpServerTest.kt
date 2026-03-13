package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlin.test.Test

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
      assertThat(tool.tool.description).isNotNull()
        .transform { it.isNotBlank() }.isEqualTo(true)
    }
  }

  @Test
  fun `tool count is exactly 12`() {
    val server = VerityMcpServer().create()
    assertThat(server.tools.size).isEqualTo(12)
  }
}
