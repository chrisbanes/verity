package me.chrisbanes.verity.core.interaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class AndroidMobileInteractionMapperTest {

  private val mapper = AndroidMobileInteractionMapper

  // Key presses
  @Test
  fun `maps press back to key press`() {
    assertThat(mapper.map("press back")).isEqualTo(Interaction.KeyPress("back"))
  }

  @Test
  fun `maps press home to key press`() {
    assertThat(mapper.map("press home")).isEqualTo(Interaction.KeyPress("home"))
  }

  @Test
  fun `maps press enter to key press`() {
    assertThat(mapper.map("press enter")).isEqualTo(Interaction.KeyPress("enter"))
  }

  @Test
  fun `maps volume keys`() {
    assertThat(mapper.map("press volume up")).isEqualTo(Interaction.KeyPress("volume up"))
    assertThat(mapper.map("press volume down")).isEqualTo(Interaction.KeyPress("volume down"))
  }

  // Tap gestures
  @Test
  fun `maps tap on text`() {
    assertThat(mapper.map("tap Settings")).isEqualTo(Interaction.TapOnText("Settings"))
  }

  @Test
  fun `maps tap on with quotes`() {
    assertThat(mapper.map("tap \"Sign In\"")).isEqualTo(Interaction.TapOnText("Sign In"))
  }

  @Test
  fun `maps click as tap`() {
    assertThat(mapper.map("click Settings")).isEqualTo(Interaction.TapOnText("Settings"))
  }

  @Test
  fun `maps tap on as tap`() {
    assertThat(mapper.map("tap on Settings")).isEqualTo(Interaction.TapOnText("Settings"))
  }

  // Scroll gestures
  @Test
  fun `maps scroll down`() {
    assertThat(mapper.map("scroll down")).isEqualTo(Interaction.Scroll(Direction.DOWN))
  }

  @Test
  fun `maps scroll up`() {
    assertThat(mapper.map("scroll up")).isEqualTo(Interaction.Scroll(Direction.UP))
  }

  // Swipe gestures
  @Test
  fun `maps swipe left`() {
    assertThat(mapper.map("swipe left")).isEqualTo(Interaction.Swipe(Direction.LEFT))
  }

  @Test
  fun `maps swipe right`() {
    assertThat(mapper.map("swipe right")).isEqualTo(Interaction.Swipe(Direction.RIGHT))
  }

  @Test
  fun `maps swipe up`() {
    assertThat(mapper.map("swipe up")).isEqualTo(Interaction.Swipe(Direction.UP))
  }

  @Test
  fun `maps swipe down`() {
    assertThat(mapper.map("swipe down")).isEqualTo(Interaction.Swipe(Direction.DOWN))
  }

  // Long press
  @Test
  fun `maps long press on text`() {
    assertThat(mapper.map("long press Settings")).isEqualTo(Interaction.LongPressOnText("Settings"))
  }

  @Test
  fun `maps hold as long press`() {
    assertThat(mapper.map("hold Settings")).isEqualTo(Interaction.LongPressOnText("Settings"))
  }

  // Pull to refresh
  @Test
  fun `maps pull to refresh`() {
    assertThat(mapper.map("pull to refresh")).isEqualTo(Interaction.PullToRefresh)
  }

  // Case insensitivity (for matching) but preserves original text casing
  @Test
  fun `is case insensitive for matching but preserves text casing`() {
    assertThat(mapper.map("Tap Settings")).isEqualTo(Interaction.TapOnText("Settings"))
    assertThat(mapper.map("SCROLL DOWN")).isEqualTo(Interaction.Scroll(Direction.DOWN))
  }

  // Unmappable
  @Test
  fun `returns null for complex instructions`() {
    assertThat(mapper.map("navigate to the settings page")).isNull()
    assertThat(mapper.map("find trending section")).isNull()
  }
}
