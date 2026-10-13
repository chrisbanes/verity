package me.chrisbanes.verity.core.parser

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import kotlin.test.Test
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform

class JourneyWarningsTest {
  private val prose = "a card in a row that opens a detail page"

  private fun journey(vararg steps: JourneyStep) = Journey(name = "j", app = "app", platform = Platform.ANDROID_TV, steps = steps.toList())

  private fun loop(until: String) = JourneyStep.Loop("Press D-pad down", until)

  @Test
  fun `prose focus conditions warn with file condition and target`() {
    for (until in listOf("$prose is focused", "focus is on $prose", "$prose has focus")) {
      val warnings = JourneyWarnings.check(journey(loop(until)), "a.yaml")
      assertThat(warnings).hasSize(1)
      assertThat(warnings.single()).contains("a.yaml")
      assertThat(warnings.single()).contains("\"$until\"")
      assertThat(warnings.single()).contains("\"$prose\"")
    }
  }

  @Test
  fun `short, quoted, visual and non focus conditions are silent`() {
    for (until in listOf("Settings is focused", "Settings menu item is focused", "\"Continue Watching\" is focused", "Settings is visible", "visually the selected card is focused")) {
      assertThat(JourneyWarnings.check(journey(loop(until)), "a.yaml")).isEmpty()
    }
    assertThat(JourneyWarnings.check(journey(JourneyStep.Action("Press home"), JourneyStep.Wait("Home")), "a.yaml")).isEmpty()
  }

  @Test
  fun `long quoted target warns`() {
    val label = "x".repeat(41)
    assertThat(JourneyWarnings.check(journey(loop("'$label' is focused")), "a.yaml")).hasSize(1)
  }

  @Test
  fun `wait conditions warn and step numbers are one based`() {
    val warnings = JourneyWarnings.check(journey(JourneyStep.Action("Press home"), JourneyStep.Wait("$prose is focused")), "a.yaml")
    assertThat(warnings).hasSize(1)
    assertThat(warnings.single()).contains("step 2")
  }
}
