package me.chrisbanes.verity.agent

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.InvalidActionFlowException
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession

class InteractionExecutorTest {

  private fun createExecutor(session: RecordingDeviceSession) = InteractionExecutor(session, APP_ID)

  @Test
  fun `key press calls pressKey`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.KeyPress("back"))
    assertThat(session.pressedKeys).isEqualTo(listOf("back"))
  }

  @Test
  fun `raw keycode is pressed directly and settles`() = runTest {
    val session = FakeDeviceSession(Platform.ANDROID_TV)
    val executor = InteractionExecutor(session, APP_ID)
    executor.execute(Interaction.KeyCode(174, longPress = true))
    executor.execute(Interaction.KeyCode(174))
    assertThat(session.pressedKeycodes).isEqualTo(listOf(174 to true, 174 to false))
    assertThat(session.executedActionFlows).isEmpty()
    assertThat(session.events).isEqualTo(listOf("key:174:true", "settle", "key:174:false", "settle"))
  }

  @Test
  fun `raw keycode on iOS fails before any device call`() = runTest {
    val session = FakeDeviceSession(Platform.IOS)
    assertFailure { InteractionExecutor(session, APP_ID).execute(Interaction.KeyCode(174)) }
      .isInstanceOf<InvalidActionFlowException>()
    assertThat(session.events).isEmpty()
  }

  @Test
  fun `tap on text generates tapOn flow`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.TapOnText("Settings"))
    assertThat(session.executedActionFlows.single()).isEqualTo(ActionFlow(APP_ID, listOf(Interaction.TapOnText("Settings"))))
  }

  @Test
  fun `tap on id generates tapOn id flow`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.TapOnId("settings_btn"))
    assertThat(session.executedActionFlows.single()).isEqualTo(ActionFlow(APP_ID, listOf(Interaction.TapOnId("settings_btn"))))
  }

  @Test
  fun `scroll generates swipe flow`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.Scroll(Direction.DOWN))
    assertThat(session.executedActionFlows.single()).isEqualTo(ActionFlow(APP_ID, listOf(Interaction.Scroll(Direction.DOWN))))
  }

  @Test
  fun `swipe generates swipe flow`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.Swipe(Direction.LEFT))
    assertThat(session.executedActionFlows.single()).isEqualTo(ActionFlow(APP_ID, listOf(Interaction.Swipe(Direction.LEFT))))
  }

  @Test
  fun `long press on text generates longPressOn flow`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.LongPressOnText("Photo"))
    assertThat(session.executedActionFlows.single()).isEqualTo(ActionFlow(APP_ID, listOf(Interaction.LongPressOnText("Photo"))))
  }

  @Test
  fun `pull to refresh generates swipe up`() = runTest {
    val session = RecordingDeviceSession()
    createExecutor(session).execute(Interaction.PullToRefresh)
    assertThat(session.executedActionFlows.single()).isEqualTo(ActionFlow(APP_ID, listOf(Interaction.PullToRefresh)))
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
  fun `extended actions execute typed with no duplicate explicit waits`() = runTest {
    for (interaction in listOf(Interaction.LaunchApp(), Interaction.InputText("private"), Interaction.DefaultScroll, Interaction.WaitForAnimation(), Interaction.WaitUntilVisible(text = "private", timeoutMs = 1000))) {
      val session = RecordingDeviceSession()
      createExecutor(session).execute(interaction)
      assertThat(session.executedActionFlows).isEqualTo(listOf(ActionFlow(APP_ID, listOf(interaction))))
      assertThat(session.waitCount).isEqualTo(if (interaction is Interaction.WaitForAnimation || interaction is Interaction.WaitUntilVisible) 0 else 1)
    }
  }

  @Test fun `direct key exceptions retain identity and stop before wait`() = runTest {
    for (failure in listOf(IllegalStateException("key failed"), CancellationException("caller"))) {
      val session = RecordingDeviceSession(onPress = { throw failure })
      assertThat(runCatching { createExecutor(session).execute(Interaction.KeyPress("back")) }.exceptionOrNull()).isSameInstanceAs(failure)
      assertThat(session.waitCount).isEqualTo(0)
      assertThat(session.executedActionFlows).isEqualTo(emptyList())
    }
  }

  /** Focus follows the press count; every capture is counted. */
  private class CountingTv {
    var captures = 0
    val session: FakeDeviceSession = FakeDeviceSession(Platform.ANDROID_TV, hierarchyProvider = {
      captures++
      HierarchyNode(attributes = mapOf("resource-id" to "node-${session.pressedKeys.size}"), states = setOf("focused"))
    })

    /** Captures spent by [block]. */
    suspend fun count(block: suspend () -> Unit): Int {
      val start = captures
      block()
      return captures - start
    }
  }

  private val down = Interaction.KeyPress("Remote Dpad Down")

  @Test
  fun `second consecutive d-pad press reuses the previous settled focus as its baseline`() = runTest {
    val tv = CountingTv()
    val executor = InteractionExecutor(tv.session, APP_ID)
    assertThat(tv.count { executor.execute(down) }).isEqualTo(2)
    assertThat(tv.count { executor.execute(down) }).isEqualTo(1)
  }

  @Test
  fun `a non-key action between presses resets the baseline`() = runTest {
    val tv = CountingTv()
    val executor = InteractionExecutor(tv.session, APP_ID)
    val first = tv.count { executor.execute(down) }
    executor.execute(Interaction.TapOnText("OK"))
    assertThat(tv.count { executor.execute(down) }).isEqualTo(first)
  }

  @Test
  fun `visual and non-TV presses never carry a baseline`() = runTest {
    val tv = CountingTv()
    val visual = InteractionExecutor(tv.session, APP_ID)
    visual.execute(down, visual = true)
    // A visual settle observes no focus, so the next press must capture its own baseline.
    assertThat(tv.count { visual.execute(down) }).isEqualTo(2)

    val mobile = RecordingDeviceSession()
    val executor = createExecutor(mobile)
    assertThat(executor.execute(down)).isEqualTo(KeySettleResult(null, null))
    assertThat(executor.execute(down)).isEqualTo(KeySettleResult(null, null))
    assertThat(mobile.pressedKeys).hasSize(2)
  }

  private companion object {
    const val APP_ID = "com.example.app"
  }

  private class RecordingDeviceSession(private val onPress: () -> Unit = {}) : DeviceSession {
    override val platform: Platform = Platform.ANDROID_MOBILE
    val pressedKeys = mutableListOf<String>()
    var waitCount = 0
    var result = FlowResult(success = true)

    val executedActionFlows = mutableListOf<ActionFlow>()
    override suspend fun executeActions(flow: ActionFlow): FlowResult {
      executedActionFlows += flow
      return result
    }

    override suspend fun executeFlow(yaml: String): FlowResult = error("Internal YAML execution forbidden")

    override suspend fun pressKey(keyName: String) {
      onPress()
      pressedKeys += keyName
    }

    override suspend fun captureHierarchyTree(): HierarchyNode = HierarchyNode()

    override suspend fun captureScreenshot(output: Path) = Unit

    override suspend fun waitForAnimationToEnd() {
      waitCount++
    }

    override fun close() = Unit
  }
}
