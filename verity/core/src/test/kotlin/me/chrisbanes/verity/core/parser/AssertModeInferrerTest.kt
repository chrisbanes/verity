package me.chrisbanes.verity.core.parser

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import me.chrisbanes.verity.core.model.AssertMode

class AssertModeInferrerTest {
  @Test
  fun `short text without visual keywords infers VISIBLE`() {
    assertThat(AssertModeInferrer.infer("Home")).isEqualTo(AssertMode.VISIBLE)
  }

  @Test
  fun `three words without visual keywords infers VISIBLE`() {
    assertThat(AssertModeInferrer.infer("TV Shows row")).isEqualTo(AssertMode.VISIBLE)
  }

  @Test
  fun `four words without visual keywords infers TREE`() {
    assertThat(AssertModeInferrer.infer("Detail page shows title")).isEqualTo(AssertMode.TREE)
  }

  @Test
  fun `text with image keyword infers VISUAL`() {
    assertThat(AssertModeInferrer.infer("Backdrop image is visible")).isEqualTo(AssertMode.VISUAL)
  }

  @Test
  fun `text with color keyword infers VISUAL`() {
    assertThat(AssertModeInferrer.infer("Button color is red")).isEqualTo(AssertMode.VISUAL)
  }

  @Test
  fun `text with icon keyword infers VISUAL`() {
    assertThat(AssertModeInferrer.infer("Play icon is displayed")).isEqualTo(AssertMode.VISUAL)
  }

  @Test
  fun `text with highlight keyword infers VISUAL`() {
    assertThat(AssertModeInferrer.infer("Selected item has highlight")).isEqualTo(AssertMode.VISUAL)
  }

  @Test
  fun `text with animation keyword infers VISUAL`() {
    assertThat(AssertModeInferrer.infer("Loading animation plays")).isEqualTo(AssertMode.VISUAL)
  }

  @Test
  fun `text with gradient keyword infers VISUAL`() {
    assertThat(AssertModeInferrer.infer("Background has gradient")).isEqualTo(AssertMode.VISUAL)
  }

  @Test
  fun `text with blur keyword infers VISUAL`() {
    assertThat(AssertModeInferrer.infer("Background blur effect")).isEqualTo(AssertMode.VISUAL)
  }

  @Test
  fun `visual keyword matching is case insensitive`() {
    assertThat(AssertModeInferrer.infer("BACKDROP IMAGE visible")).isEqualTo(AssertMode.VISUAL)
  }

  @Test
  fun `longer text without visual keywords infers TREE`() {
    assertThat(AssertModeInferrer.infer("Synopsis contains at least 2 sentences"))
      .isEqualTo(AssertMode.TREE)
  }

  @Test
  fun `single word infers VISIBLE`() {
    assertThat(AssertModeInferrer.infer("Settings")).isEqualTo(AssertMode.VISIBLE)
  }

  @Test
  fun `empty string infers VISIBLE`() {
    assertThat(AssertModeInferrer.infer("")).isEqualTo(AssertMode.VISIBLE)
  }
}
