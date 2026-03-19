package me.chrisbanes.verity.core.interaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class AndroidTvInteractionMapperTest {

  private val mapper = AndroidTvInteractionMapper

  @Test
  fun `maps d-pad down to key press`() {
    assertThat(mapper.map("press d-pad down")).isEqualTo(Interaction.KeyPress("Remote Dpad Down"))
  }

  @Test
  fun `maps d-pad up to key press`() {
    assertThat(mapper.map("press d-pad up")).isEqualTo(Interaction.KeyPress("Remote Dpad Up"))
  }

  @Test
  fun `maps d-pad left to key press`() {
    assertThat(mapper.map("press d-pad left")).isEqualTo(Interaction.KeyPress("Remote Dpad Left"))
  }

  @Test
  fun `maps d-pad right to key press`() {
    assertThat(mapper.map("press d-pad right")).isEqualTo(Interaction.KeyPress("Remote Dpad Right"))
  }

  @Test
  fun `maps select to d-pad center`() {
    assertThat(mapper.map("press select")).isEqualTo(Interaction.KeyPress("Remote Dpad Center"))
  }

  @Test
  fun `maps d-pad center`() {
    assertThat(mapper.map("press d-pad center")).isEqualTo(Interaction.KeyPress("Remote Dpad Center"))
  }

  @Test
  fun `maps back`() {
    assertThat(mapper.map("press back")).isEqualTo(Interaction.KeyPress("back"))
  }

  @Test
  fun `maps home`() {
    assertThat(mapper.map("press home")).isEqualTo(Interaction.KeyPress("home"))
  }

  @Test
  fun `maps media keys`() {
    assertThat(mapper.map("press menu")).isEqualTo(Interaction.KeyPress("Remote Media Menu"))
    assertThat(mapper.map("press play")).isEqualTo(Interaction.KeyPress("Remote Media Play Pause"))
    assertThat(mapper.map("press rewind")).isEqualTo(Interaction.KeyPress("Remote Media Rewind"))
  }

  @Test
  fun `is case insensitive`() {
    assertThat(mapper.map("Press D-Pad Down")).isEqualTo(Interaction.KeyPress("Remote Dpad Down"))
  }

  @Test
  fun `returns null for unknown instruction`() {
    assertThat(mapper.map("navigate to settings")).isNull()
  }
}
