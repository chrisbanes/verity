package me.chrisbanes.verity.core.timing

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest

class RecordingPhaseTimerTest {
  private var now = 0L
  private val timer = RecordingPhaseTimer { now }

  private fun advance(ms: Long) {
    now += ms * 1_000_000
  }

  @Test
  fun stepTotalsSeparateModelAndDeviceAndDoNotSumDetail() = runTest {
    timer.step("j segment 1") {
      timer.phase(PhaseKind.DEVICE, "pressKey") { advance(300) }
      timer.phase(PhaseKind.MODEL_REQUEST, "navigator") {
        timer.phase(PhaseKind.MODEL_DETAIL, "turn/await") { advance(600) }
        advance(100)
      }
      advance(50)
    }
    val step = timer.report().steps.single()
    assertThat(step.total).isEqualTo(1050.milliseconds)
    assertThat(step.deviceTotal).isEqualTo(300.milliseconds)
    assertThat(step.modelTotal).isEqualTo(700.milliseconds)
    assertThat(step.phases.single { it.kind == PhaseKind.MODEL_DETAIL }).isEqualTo(PhaseTiming(PhaseKind.MODEL_DETAIL, "turn/await", 1, 600.milliseconds))
  }

  @Test
  fun samePhaseAggregates() = runTest {
    timer.step("s") {
      timer.phase(PhaseKind.DEVICE, "pressKey") { advance(10) }
      timer.phase(PhaseKind.DEVICE, "pressKey") { advance(20) }
    }
    assertThat(timer.report().steps.single().phases).containsExactly(PhaseTiming(PhaseKind.DEVICE, "pressKey", 2, 30.milliseconds))
  }

  @Test
  fun throwingBlockStillRecordsAndPropagates() = runTest {
    val result = runCatching {
      timer.step("s") {
        timer.phase(PhaseKind.DEVICE, "x") {
          advance(5)
          error("boom")
        }
      }
    }
    assertThat(result).isFailure().isInstanceOf<IllegalStateException>()
    val step = timer.report().steps.single()
    assertThat(step.total).isEqualTo(5.milliseconds)
    assertThat(step.deviceTotal).isEqualTo(5.milliseconds)
  }

  @Test
  fun phaseOutsideStepAndStepOrder() = runTest {
    timer.phase(PhaseKind.DEVICE, "x") { advance(1) }
    timer.step("a") {}
    timer.step("b") {}
    assertThat(timer.report().steps.map { it.label }).containsExactly("(no step)", "a", "b")
  }

  @Test
  fun renderFormat() = runTest {
    timer.step("j setup") {
      timer.phase(PhaseKind.DEVICE, "executeActions") { advance(30) }
      timer.phase(PhaseKind.MODEL_REQUEST, "navigator") { advance(70) }
    }
    assertThat(timer.report().render()).isEqualTo(
      """
      Timing
      j setup: 100ms (model 70ms, device 30ms)
        device executeActions x1 30ms
        model_request navigator x1 70ms
      """.trimIndent(),
    )
  }

  @Test
  fun noOpReturnsValue() = runTest {
    assertThat(NoOpPhaseTimer.step("s") { NoOpPhaseTimer.phase(PhaseKind.DEVICE, "x") { 7 } }).isEqualTo(7)
  }
}
