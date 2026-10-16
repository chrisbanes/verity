package me.chrisbanes.verity.device

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.timing.PhaseKind
import me.chrisbanes.verity.core.timing.PhaseTiming
import me.chrisbanes.verity.core.timing.RecordingPhaseTimer

class TimingDeviceSessionTest {
  private var now = 0L
  private val timer = RecordingPhaseTimer { now }
  private val fake = FakeDeviceSession(Platform.ANDROID_TV, HierarchyNode(attributes = mapOf("text" to "x", "focused" to "true")))
  private var failWith: Exception? = null

  // Every delegate call costs 10ms; delegating to FakeDeviceSession keeps the interface default bodies real.
  private val delegate = object : DeviceSession by fake {
    override suspend fun executeFlow(yaml: String): FlowResult = tick { fake.executeFlow(yaml) }

    override suspend fun executeActions(flow: ActionFlow): FlowResult = tick { fake.executeActions(flow) }

    override suspend fun pressKey(keyName: String) = tick { fake.pressKey(keyName) }

    override suspend fun pressKey(keycode: Int, longPress: Boolean) = tick { fake.pressKey(keycode, longPress) }

    override suspend fun captureHierarchyTree(): HierarchyNode = tick { fake.captureHierarchyTree() }

    override suspend fun captureScreenshot(output: Path) = tick { fake.captureScreenshot(output) }

    override suspend fun waitForAnimationToEnd() = tick { fake.waitForAnimationToEnd() }

    private suspend fun <T> tick(block: suspend () -> T): T {
      now += 10_000_000
      failWith?.let { throw it }
      return block()
    }
  }
  private val session = TimingDeviceSession(delegate, timer)

  @Test
  fun recordsOnePhasePerCallWithoutDoubleCountingDefaults() = runTest {
    timer.step("s") {
      session.pressKey("remote dpad down")
      session.pressKey(20, false)
      session.waitForAnimationToEnd()
      session.captureHierarchyTree()
      session.captureHierarchy()
      assertThat(session.containsText("x")).isTrue()
      assertThat(session.checkFocused("x")).isEqualTo(fake.checkFocused("x"))
      session.captureScreenshot(Path.of("s.png"))
      session.executeActions(ActionFlow("app", emptyList()))
    }
    val phases = timer.report().steps.single().phases
    assertThat(phases.map { it.name to it.count }).containsExactly(
      "pressKey" to 2,
      "waitForAnimationToEnd" to 1,
      "captureHierarchyTree" to 1,
      "captureHierarchy" to 1,
      "containsText" to 1,
      "checkFocused" to 1,
      "captureScreenshot" to 1,
      "executeActions" to 1,
    )
    assertThat(phases.first()).isEqualTo(PhaseTiming(PhaseKind.DEVICE, "pressKey", 2, 20.milliseconds))
  }

  @Test
  fun recordsStabilitySettleAsItsOwnPhase() = runTest {
    timer.step("s") { session.awaitStableHierarchy(2.seconds) }
    val phases = timer.report().steps.single().phases
    assertThat(phases.map { Triple(it.kind, it.name, it.count) }).containsExactly(Triple(PhaseKind.DEVICE, "awaitStableHierarchy", 1))
  }

  @Test
  fun passesThroughPropertiesAndClose() {
    assertThat(session.platform).isEqualTo(Platform.ANDROID_TV)
    assertThat(session.supportsRawKeycodes).isTrue()
    assertThat(session.supportsLongPress).isTrue()
    session.close()
    assertThat(fake.closed).isTrue()
  }

  @Test
  fun delegateFailurePropagatesAndIsRecorded() = runTest {
    failWith = IllegalStateException("adb down")
    val result = runCatching { timer.step("s") { session.pressKey("x") } }
    assertThat(result).isFailure().isInstanceOf<IllegalStateException>()
    assertThat(timer.report().steps.single().deviceTotal).isEqualTo(10.milliseconds)
    assertThat(fake.pressedKeys.isEmpty()).isTrue()
  }
}
