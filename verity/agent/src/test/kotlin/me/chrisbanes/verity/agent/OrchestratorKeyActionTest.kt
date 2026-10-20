package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.context.KeyAction
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.ActionFlowInvalidReason
import me.chrisbanes.verity.core.model.InvalidActionFlowException
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.SegmentExecutionMode
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession

class OrchestratorKeyActionTest {
  @Test
  fun `key action step presses its keycode without a navigator call on TV and mobile`() = runTest {
    for (platform in listOf(Platform.ANDROID_TV, Platform.ANDROID_MOBILE)) {
      val session = FakeDeviceSession(platform)
      val result = run(session, UNUSED_NAVIGATOR, platform, JourneyStep.Action(" open quick ACTIONS "))

      assertThat(result.passed).isEqualTo(true)
      assertThat(result.segments.single().executionMode).isEqualTo(SegmentExecutionMode.FAST)
      assertThat(session.events.drop(1).first()).isEqualTo("key:174:false")
    }
  }

  @Test
  fun `long-press key action holds the key`() = runTest {
    val session = FakeDeviceSession(Platform.ANDROID_TV)
    run(session, UNUSED_NAVIGATOR, Platform.ANDROID_TV, JourneyStep.Action("Hold Quick Actions"))

    assertThat(session.pressedKeycodes).isEqualTo(listOf(174 to true))
  }

  @Test
  fun `loop body resolves key actions without a navigator call`() = runTest {
    val session = FakeDeviceSession(Platform.ANDROID_TV)
    run(session, UNUSED_NAVIGATOR, Platform.ANDROID_TV, JourneyStep.Loop("Open Quick Actions", "page is ready", 1))

    assertThat(session.events.drop(1)).isEqualTo(listOf("key:174:false", "settle"))
  }

  @Test
  fun `group mixing a key action with an unmapped step goes to the navigator and presses the key directly`() = runTest {
    val session = FakeDeviceSession(Platform.ANDROID_TV)
    var requested: List<String>? = null
    val navigator = NavigatorAgent("ctx") { _, messages ->
      requested = messages.map { it.toString() }
      modelReply("""{"actions":[{"type":"keyCode","keycode":174},{"type":"tapOnText","text":"Settings"}]}""")
    }
    run(session, navigator, Platform.ANDROID_TV, JourneyStep.Action("Open Quick Actions"), JourneyStep.Action("Choose Settings from the menu"))

    assertThat(requested).isNotNull()
    assertThat(session.events.drop(1)).isEqualTo(listOf("key:174:false", "settle", "flow"))
  }

  @Test
  fun `key action on iOS is rejected before any device work`() = runTest {
    val session = FakeDeviceSession(Platform.IOS)
    val error = runCatching { run(session, UNUSED_NAVIGATOR, Platform.IOS, JourneyStep.Action("Open Quick Actions")) }.exceptionOrNull()

    assertThat(error).isNotNull().isInstanceOf<InvalidActionFlowException>()
      .transform { it.reason }
      .isEqualTo(ActionFlowInvalidReason.UNSUPPORTED_INTERACTION)
    assertThat(session.pressedKeycodes).isEmpty()
    assertThat(session.executedActionFlows).isEqualTo(listOf(ActionFlow(APP_ID, listOf(Interaction.LaunchApp()))))
  }

  @Test
  fun `without key actions the same step goes to the navigator`() = runTest {
    val session = FakeDeviceSession(Platform.ANDROID_TV)
    var called = false
    val navigator = NavigatorAgent("ctx") { _, _ ->
      called = true
      modelReply("""{"actions":[{"type":"tapOnText","text":"A"}]}""")
    }
    Orchestrator(session, { navigator }, { INSPECTOR }).run(Journey("keys", APP_ID, Platform.ANDROID_TV, listOf(JourneyStep.Action("Open Quick Actions"))))

    assertThat(called).isEqualTo(true)
  }

  private suspend fun run(session: DeviceSession, navigator: NavigatorAgent, platform: Platform, vararg steps: JourneyStep) = Orchestrator(session, { navigator }, { INSPECTOR }, keyActions = KEYS)
    .run(Journey("keys", APP_ID, platform, steps.toList()))

  private companion object {
    const val APP_ID = "com.example.app"
    val KEYS = listOf(
      KeyAction("Open Quick Actions", 174, longPress = false, file = File("remote.md"), line = 3),
      KeyAction("Hold Quick Actions", 174, longPress = true, file = File("remote.md"), line = 4),
    )
    val UNUSED_NAVIGATOR = NavigatorAgent("ctx") { _, _ -> error("unexpected navigator") }
    val INSPECTOR = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"continue"}""") },
      evaluateVisualContent = { _, _, _, _ -> error("unused") },
    )
  }
}
