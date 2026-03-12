package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession

class McpDeviceSessionManagerTest {

  private class DummyDeviceSession : DeviceSession {
    var closed = false
    override val platform: Platform = Platform.ANDROID_MOBILE

    override suspend fun executeFlow(yaml: String): FlowResult = FlowResult(success = true)
    override suspend fun pressKey(keyName: String) {}
    override suspend fun captureHierarchyTree(): HierarchyNode = HierarchyNode(attributes = emptyMap())
    override suspend fun captureScreenshot(output: Path) {}
    override suspend fun shell(command: String): String = ""
    override suspend fun waitForAnimationToEnd() {}

    override fun close() {
      closed = true
    }
  }

  @Test
  fun `session handle contains id and device info`() {
    val handle = SessionHandle(
      sessionId = java.util.UUID.randomUUID(),
      deviceId = "emulator-5554",
    )
    assertThat(handle.deviceId).isEqualTo("emulator-5554")
    assertThat(handle.sessionId).isNotNull()
  }

  @Test
  fun `open returns session handle and stores session`() = runTest {
    val dummySession = DummyDeviceSession()
    val manager = McpDeviceSessionManager(sessionFactory = { _, _, _ -> dummySession })

    val handle = manager.open(Platform.ANDROID_MOBILE, "test-device")

    assertThat(handle.deviceId).isEqualTo("test-device")
    assertThat(manager.isOpen(handle.sessionId)).isTrue()
  }

  @Test
  fun `withSession executes block and updates lastUsedAt`() = runTest {
    val dummySession = DummyDeviceSession()
    val manager = McpDeviceSessionManager(sessionFactory = { _, _, _ -> dummySession })
    val handle = manager.open(Platform.ANDROID_MOBILE, "test-device")

    val result = manager.withSession(handle.sessionId) { session ->
      assertThat(session).isEqualTo(dummySession)
      "success"
    }

    assertThat(result).isEqualTo("success")
  }

  @Test
  fun `close removes session and calls close on device session`() = runTest {
    val dummySession = DummyDeviceSession()
    val manager = McpDeviceSessionManager(sessionFactory = { _, _, _ -> dummySession })
    val handle = manager.open(Platform.ANDROID_MOBILE, "test-device")

    manager.close(handle.sessionId)

    assertThat(manager.isOpen(handle.sessionId)).isFalse()
    assertThat(dummySession.closed).isTrue()
  }

  @Test
  fun `withSession throws on invalid id`() = runTest {
    val manager = McpDeviceSessionManager()

    assertFailsWith<IllegalArgumentException> {
      manager.withSession(java.util.UUID.randomUUID()) { }
    }
  }

  @Test
  fun `close throws on invalid id`() = runTest {
    val manager = McpDeviceSessionManager()

    assertFailsWith<IllegalArgumentException> {
      manager.close(java.util.UUID.randomUUID())
    }
  }
}
