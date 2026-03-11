package me.chrisbanes.verity.core.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test

class JourneyStepTest {
  @Test
  fun `action step holds instruction`() {
    val step: JourneyStep = JourneyStep.Action("Press D-pad down")
    assertThat(step).isInstanceOf<JourneyStep.Action>()
    assertThat((step as JourneyStep.Action).instruction).isEqualTo("Press D-pad down")
  }

  @Test
  fun `assert step holds description and mode`() {
    val step = JourneyStep.Assert("Home", AssertMode.VISIBLE)
    assertThat(step.description).isEqualTo("Home")
    assertThat(step.mode).isEqualTo(AssertMode.VISIBLE)
  }

  @Test
  fun `loop step has default max of 20`() {
    val step = JourneyStep.Loop(action = "Press D-pad down", until = "TV Shows")
    assertThat(step.max).isEqualTo(20)
  }
}
