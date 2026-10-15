package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class ConditionPrecheckTest {
  @Test
  fun `state is the accessibility tree alone without reference text`() {
    assertThat(precheckState("H", InspectionContext())).isEqualTo("Accessibility tree:\nH")
  }

  @Test
  fun `state appends reference text when present`() {
    assertThat(precheckState("H", InspectionContext(referenceText = "R")))
      .isEqualTo("Accessibility tree:\nH\n\nReference context (earlier observations, not proof of the current condition):\nR")
  }
}
