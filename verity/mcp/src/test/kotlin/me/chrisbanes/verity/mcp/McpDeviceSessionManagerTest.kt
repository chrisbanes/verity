package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlin.test.Test

class McpDeviceSessionManagerTest {
  @Test
  fun `session handle contains id and device info`() {
    val handle = SessionHandle(
      sessionId = java.util.UUID.randomUUID(),
      deviceId = "emulator-5554",
    )
    assertThat(handle.deviceId).isEqualTo("emulator-5554")
    assertThat(handle.sessionId).isNotNull()
  }
}
