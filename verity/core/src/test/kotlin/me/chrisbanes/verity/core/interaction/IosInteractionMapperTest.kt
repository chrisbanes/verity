package me.chrisbanes.verity.core.interaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class IosInteractionMapperTest {

  private val mapper = IosInteractionMapper

  @Test
  fun `maps press home`() {
    assertThat(mapper.map("press home")).isEqualTo(Interaction.KeyPress("home"))
  }

  @Test
  fun `maps press lock`() {
    assertThat(mapper.map("press lock")).isEqualTo(Interaction.KeyPress("lock"))
  }

  @Test
  fun `maps volume keys`() {
    assertThat(mapper.map("press volume up")).isEqualTo(Interaction.KeyPress("volume up"))
    assertThat(mapper.map("press volume down")).isEqualTo(Interaction.KeyPress("volume down"))
  }

  @Test
  fun `does not map press back`() {
    assertThat(mapper.map("press back")).isNull()
  }

  @Test
  fun `maps tap on text`() {
    assertThat(mapper.map("tap Settings")).isEqualTo(Interaction.TapOnText("Settings"))
  }

  @Test
  fun `maps scroll down`() {
    assertThat(mapper.map("scroll down")).isEqualTo(Interaction.Scroll(Direction.DOWN))
  }

  @Test
  fun `maps swipe left`() {
    assertThat(mapper.map("swipe left")).isEqualTo(Interaction.Swipe(Direction.LEFT))
  }

  @Test
  fun `maps long press`() {
    assertThat(mapper.map("long press Photos")).isEqualTo(Interaction.LongPressOnText("Photos"))
  }

  @Test
  fun `maps pull to refresh`() {
    assertThat(mapper.map("pull to refresh")).isEqualTo(Interaction.PullToRefresh)
  }

  @Test
  fun `returns null for complex instructions`() {
    assertThat(mapper.map("navigate to settings")).isNull()
  }
}
