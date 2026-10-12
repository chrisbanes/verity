package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.ActionFlowInvalidReason
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.InvalidActionFlowException
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession

class OrchestratorRawKeycodeTest {
  @Test
  fun `generated flow runs maestro sub-flows with direct key presses between them in order`() = runTest {
    val session = FakeDeviceSession(Platform.ANDROID_TV)
    val result = run(session, navigator(MIXED), JourneyStep.Action(SLOW_STEP))

    assertThat(result.passed).isEqualTo(true)
    assertThat(session.events.drop(1)).isEqualTo(listOf("flow", "key:174:true", "settle", "flow"))
    assertThat(session.executedActionFlows.drop(1)).isEqualTo(
      listOf(ActionFlow(APP_ID, listOf(Interaction.TapOnText("A"))), ActionFlow(APP_ID, listOf(Interaction.TapOnText("B")))),
    )
    assertThat(session.pressedKeycodes).isEqualTo(listOf(174 to true))
  }

  @Test
  fun `leading and trailing keycodes produce no empty sub-flow`() = runTest {
    val session = FakeDeviceSession(Platform.ANDROID_TV)
    run(session, navigator("""[$KEY,{"type":"tapOnText","text":"A"},$KEY]"""), JourneyStep.Action(SLOW_STEP))

    assertThat(session.events.drop(1)).isEqualTo(listOf("key:174:true", "settle", "flow", "key:174:true", "settle"))
    assertThat(session.executedActionFlows.drop(1)).isEqualTo(listOf(ActionFlow(APP_ID, listOf(Interaction.TapOnText("A")))))
  }

  @Test
  fun `flow without keycodes reaches executeActions once unchanged`() = runTest {
    val session = FakeDeviceSession(Platform.ANDROID_TV)
    var selected: ActionFlow? = null
    val navigator = NavigatorAgent("ctx", validateFlow = { selected = it }) { _, _ ->
      modelReply("""{"actions":[{"type":"tapOnText","text":"A"},{"type":"tapOnText","text":"B"}]}""")
    }
    run(session, navigator, JourneyStep.Action(SLOW_STEP))

    assertThat(session.events.drop(1)).isEqualTo(listOf("flow"))
    assertThat(session.executedActionFlows.last()).isSameInstanceAs(selected)
  }

  @Test
  fun `failed sub-flow stops before the following key press`() = runTest {
    val fake = FakeDeviceSession(Platform.ANDROID_TV)
    val session = object : DeviceSession by fake {
      override suspend fun executeActions(flow: ActionFlow): FlowResult {
        val result = fake.executeActions(flow)
        return if (flow.actions == listOf(Interaction.LaunchApp())) result else FlowResult(false, output = "failed")
      }
    }
    val result = run(session, navigator(MIXED), JourneyStep.Action(SLOW_STEP))

    assertThat(result.passed).isFalse()
    assertThat(fake.events.drop(1)).isEqualTo(listOf("flow"))
    assertThat(fake.pressedKeycodes).isEmpty()
  }

  @Test
  fun `generated loop body runs the same interleave each iteration`() = runTest {
    val session = FakeDeviceSession(Platform.ANDROID_TV)
    run(session, navigator(MIXED), JourneyStep.Loop("Press D-pad down; $SLOW_STEP", "page is ready", 2))

    val body = listOf("flow", "key:174:true", "settle", "flow")
    assertThat(session.events.drop(1)).isEqualTo(body + body)
  }

  @Test
  fun `mapped step and mapped loop body press raw keycodes in order`() = runTest {
    val step = FakeDeviceSession(Platform.ANDROID_TV)
    run(step, UNUSED_NAVIGATOR, JourneyStep.Action("Press keycode 174"))
    assertThat(step.pressedKeycodes).isEqualTo(listOf(174 to false))

    val loop = FakeDeviceSession(Platform.ANDROID_TV)
    run(loop, UNUSED_NAVIGATOR, JourneyStep.Loop("Press back; Press keycode 174", "page is ready", 1))
    assertThat(loop.events.drop(1)).isEqualTo(listOf("name:back", "settle", "key:174:false", "settle"))
  }

  @Test
  fun `unsupported session rejects generated keycodes before any device work or artifact`() = runTest {
    val ios = FakeDeviceSession(Platform.IOS)
    val recorder = GeneratedFlowRecorder()
    assertUnsupported { run(ios, navigator(MIXED), JourneyStep.Action(SLOW_STEP), Platform.IOS, recorder) }
    assertThat(ios.executedActionFlows).isEqualTo(listOf(LAUNCH))
    assertThat(ios.pressedKeycodes).isEmpty()
    assertThat(recorder.flows).isEmpty()

    val mapped = FakeDeviceSession(Platform.IOS)
    val mappedLoop = JourneyStep.Loop("Press back; Press keycode 174", "page is ready", 1)
    // iOS maps neither step, so the navigator decides; a keycode reply is still rejected.
    assertUnsupported { run(mapped, navigator("[$KEY]"), mappedLoop, Platform.IOS) }
    assertThat(mapped.events.drop(1)).isEmpty()
  }

  @Test
  fun `long press keycode needs long press support while a short keycode runs`() = runTest {
    val fake = FakeDeviceSession(Platform.ANDROID_TV)
    val noLongPress = object : DeviceSession by fake {
      override val supportsLongPress = false
    }
    assertUnsupported { run(noLongPress, navigator(MIXED), JourneyStep.Action(SLOW_STEP)) }
    assertThat(fake.events.drop(1)).isEmpty()

    run(noLongPress, UNUSED_NAVIGATOR, JourneyStep.Action("Press keycode 174"))
    assertThat(fake.pressedKeycodes).isEqualTo(listOf(174 to false))
  }

  private suspend fun run(
    session: DeviceSession,
    navigator: NavigatorAgent,
    step: JourneyStep,
    platform: Platform = Platform.ANDROID_TV,
    recorder: JourneyArtifactRecorder = NoOpJourneyArtifactRecorder,
  ) = Orchestrator(session, { navigator }, { INSPECTOR }, artifactRecorder = recorder)
    .run(Journey("keycodes", APP_ID, platform, listOf(step)))

  private suspend fun assertUnsupported(block: suspend () -> Unit) {
    val error = runCatching { block() }.exceptionOrNull()
    assertThat(error).isNotNull().isInstanceOf<InvalidActionFlowException>()
      .transform { it.reason }
      .isEqualTo(ActionFlowInvalidReason.UNSUPPORTED_INTERACTION)
  }

  private fun navigator(actions: String) = NavigatorAgent("ctx") { _, _ -> modelReply("""{"actions":$actions}""") }

  private class GeneratedFlowRecorder : JourneyArtifactRecorder {
    val flows = mutableListOf<String>()
    override suspend fun saveGeneratedFlow(segmentIndex: Int, label: String, yaml: String): String? {
      flows += yaml
      return null
    }
  }

  private companion object {
    const val APP_ID = "com.example.app"
    const val SLOW_STEP = "Press the Bookmark key to open Quick Actions"
    const val KEY = """{"type":"keyCode","keycode":174,"longPress":true}"""
    const val MIXED = """[{"type":"tapOnText","text":"A"},$KEY,{"type":"tapOnText","text":"B"}]"""
    val LAUNCH = ActionFlow(APP_ID, listOf(Interaction.LaunchApp()))
    val UNUSED_NAVIGATOR = NavigatorAgent("ctx") { _, _ -> error("unexpected navigator") }
    val INSPECTOR = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"continue"}""") },
      evaluateVisualContent = { _, _, _, _ -> error("unused") },
    )
  }
}
