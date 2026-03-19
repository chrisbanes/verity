package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession

class InteractionExecutorTest {

  @Test
  fun `key press calls pressKey`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.KeyPress("back"))
    assertThat(session.pressedKeys).isEqualTo(listOf("back"))
  }

  @Test
  fun `tap on text generates tapOn flow`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.TapOnText("Settings"))
    assertThat(session.executedFlows.single()).isEqualTo("- tapOn:\n    text: \"Settings\"")
  }

  @Test
  fun `tap on id generates tapOn id flow`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.TapOnId("settings_btn"))
    assertThat(session.executedFlows.single()).isEqualTo("- tapOn:\n    id: \"settings_btn\"")
  }

  @Test
  fun `scroll generates scroll flow`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.Scroll(Direction.DOWN))
    assertThat(session.executedFlows.single()).isEqualTo("- scroll:\n    direction: DOWN")
  }

  @Test
  fun `swipe generates swipe flow`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.Swipe(Direction.LEFT))
    assertThat(session.executedFlows.single()).isEqualTo("- swipe:\n    direction: LEFT")
  }

  @Test
  fun `long press on text generates longPressOn flow`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.LongPressOnText("Photo"))
    assertThat(session.executedFlows.single()).isEqualTo("- longPressOn:\n    text: \"Photo\"")
  }

  @Test
  fun `pull to refresh generates scroll up`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.PullToRefresh)
    assertThat(session.executedFlows.single()).isEqualTo("- scroll:\n    direction: UP")
  }

  @Test
  fun `waits for animation after each interaction`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.KeyPress("back"))
    assertThat(session.waitCount).isEqualTo(1)
    executor.execute(Interaction.TapOnText("OK"))
    assertThat(session.waitCount).isEqualTo(2)
  }

  private class RecordingDeviceSession : DeviceSession {
    override val platform: Platform = Platform.ANDROID_MOBILE
    val pressedKeys = mutableListOf<String>()
    val executedFlows = mutableListOf<String>()
    var waitCount = 0

    override suspend fun executeFlow(yaml: String): FlowResult {
      executedFlows += yaml
      return FlowResult(success = true)
    }

    override suspend fun pressKey(keyName: String) {
      pressedKeys += keyName
    }

    override suspend fun captureHierarchyTree(): HierarchyNode = HierarchyNode()

    override suspend fun captureScreenshot(output: Path) = Unit

    override suspend fun shell(command: String): String = ""

    override suspend fun waitForAnimationToEnd() {
      waitCount++
    }

    override fun close() = Unit
  }
}
