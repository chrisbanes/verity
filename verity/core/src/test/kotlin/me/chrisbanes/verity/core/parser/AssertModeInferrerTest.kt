package me.chrisbanes.verity.core.parser

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import me.chrisbanes.verity.core.model.AssertMode

class AssertModeInferrerTest {
  private val treeCases = listOf(
    "Home",
    "Settings",
    "TV Shows row",
    "Check that the playing thumbnail is focused",
    "Check that each rail item shows a poster and a title",
    "The dialog appears",
    "Play icon is displayed",
    "Backdrop image is visible",
    "Synopsis contains at least 2 sentences",
    "",
  )

  private val visualCases = listOf(
    "Button color is red",
    "The title looks polished",
    "The title looked fine",
    "The overlay appears blurred",
    "Cards have shadows",
    "Loading animation plays",
    "Background has gradient",
    "Text opacity is reduced",
    "Border colour is blue",
    "The badge is colored green",
    "The banner is animated",
    "BACKGROUND HAS GRADIENT.",
    "Is the (color) right?",
  )

  @Test
  fun `descriptions without appearance cues infer TREE`() {
    treeCases.forEach { assertThat(AssertModeInferrer.infer(it), it).isEqualTo(AssertMode.TREE) }
  }

  @Test
  fun `appearance cues infer VISUAL`() {
    visualCases.forEach { assertThat(AssertModeInferrer.infer(it), it).isEqualTo(AssertMode.VISUAL) }
  }
}
