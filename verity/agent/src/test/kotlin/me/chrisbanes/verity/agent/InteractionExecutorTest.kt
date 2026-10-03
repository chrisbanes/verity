package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.ActionFlowInvalidReason
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.InvalidActionFlowException
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession

class InteractionExecutorTest {

  private fun createExecutor(session: RecordingDeviceSession) = InteractionExecutor(session, APP_ID)

  @Test
  fun `key press calls pressKey`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.KeyPress("back"))
    assertThat(session.pressedKeys).isEqualTo(listOf("back"))
  }

  @Test
  fun `tap on text generates tapOn flow`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.TapOnText("Settings"))
    assertThat(session.executedFlows.single()).isEqualTo(flow("- tapOn: \"Settings\""))
  }

  @Test
  fun `tap on id generates tapOn id flow`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.TapOnId("settings_btn"))
    assertThat(session.executedFlows.single()).isEqualTo(flow("- tapOn:\n    id: \"settings_btn\""))
  }

  @Test
  fun `scroll generates swipe flow`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.Scroll(Direction.DOWN))
    assertThat(session.executedFlows.single()).isEqualTo(flow("- swipe:\n    direction: DOWN"))
  }

  @Test
  fun `swipe generates swipe flow`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.Swipe(Direction.LEFT))
    assertThat(session.executedFlows.single()).isEqualTo(flow("- swipe:\n    direction: LEFT"))
  }

  @Test
  fun `long press on text generates longPressOn flow`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.LongPressOnText("Photo"))
    assertThat(session.executedFlows.single()).isEqualTo(flow("- longPressOn: \"Photo\""))
  }

  @Test
  fun `pull to refresh generates swipe up`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.PullToRefresh)
    assertThat(session.executedFlows.single()).isEqualTo(flow("- swipe:\n    direction: UP"))
  }

  @Test
  fun `waits for animation after each interaction`() = runTest {
    val session = RecordingDeviceSession()
    val executor = createExecutor(session)
    executor.execute(Interaction.KeyPress("back"))
    assertThat(session.waitCount).isEqualTo(1)
    executor.execute(Interaction.TapOnText("OK"))
    assertThat(session.waitCount).isEqualTo(2)
  }

  @Test
  fun `failed mapped flow throws typed result before animation wait`() = runTest {
    val failure = FlowResult(success = false, output = "tap failed")
    val session = RecordingDeviceSession().apply { result = failure }
    val thrown = assertFailsWith<InteractionExecutionFailure> {
      createExecutor(session).execute(Interaction.TapOnText("Settings"))
    }
    assertThat(thrown.flowResult).isEqualTo(failure)
    assertThat(session.waitCount).isEqualTo(0)
  }

  @Test
  fun `new vocabulary is rejected safely by unmigrated executor before any session operation`() = runTest {
    for (interaction in listOf(
      Interaction.LaunchApp(),
      Interaction.InputText("private"),
      Interaction.DefaultScroll,
      Interaction.WaitForAnimation(),
      Interaction.WaitUntilVisible(text = "private", timeoutMs = 1000),
    )) {
      val session = RecordingDeviceSession()
      val error = assertFailsWith<InvalidActionFlowException> { createExecutor(session).execute(interaction) }
      assertThat(error.reason).isEqualTo(ActionFlowInvalidReason.UNSUPPORTED_INTERACTION)
      assertThat(error.message).isEqualTo("Invalid action flow: UNSUPPORTED_INTERACTION")
      assertThat(session.pressedKeys).isEqualTo(emptyList())
      assertThat(session.executedFlows).isEqualTo(emptyList())
      assertThat(session.executedActionFlows).isEqualTo(emptyList())
      assertThat(session.waitCount).isEqualTo(0)
    }
  }

  private companion object {
    const val APP_ID = "com.example.app"
    fun flow(command: String) = "appId: $APP_ID\n---\n$command"
  }

  private class RecordingDeviceSession : DeviceSession {
    override val platform: Platform = Platform.ANDROID_MOBILE
    val pressedKeys = mutableListOf<String>()
    val executedFlows = mutableListOf<String>()
    var waitCount = 0
    var result = FlowResult(success = true)

    val executedActionFlows = mutableListOf<ActionFlow>()
    override suspend fun executeActions(flow: ActionFlow): FlowResult {
      executedActionFlows += flow
      return FlowResult(success = true)
    }

    override suspend fun executeFlow(yaml: String): FlowResult {
      executedFlows += yaml
      return result
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
