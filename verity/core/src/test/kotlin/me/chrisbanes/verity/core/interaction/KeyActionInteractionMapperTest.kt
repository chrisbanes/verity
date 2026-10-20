package me.chrisbanes.verity.core.interaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import java.io.File
import kotlin.test.Test
import me.chrisbanes.verity.core.context.KeyAction
import me.chrisbanes.verity.core.model.Platform

class KeyActionInteractionMapperTest {

  private val keys = listOf(
    KeyAction("Open Quick Actions", 174, longPress = false, file = File("remote.md"), line = 3),
    KeyAction("Hold Quick Actions", 174, longPress = true, file = File("remote.md"), line = 4),
  )

  @Test
  fun `key actions resolve ignoring case and surrounding whitespace`() {
    val mapper = InteractionMapper.forPlatform(Platform.ANDROID_TV, keys)

    assertThat(mapper.map(" open QUICK actions ")).isEqualTo(Interaction.KeyCode(174, longPress = false))
    assertThat(mapper.map("hold quick actions")).isEqualTo(Interaction.KeyCode(174, longPress = true))
  }

  @Test
  fun `built-ins still resolve and partial matches do not`() {
    val mapper = InteractionMapper.forPlatform(Platform.ANDROID_MOBILE, keys)

    assertThat(mapper.map("press back")).isEqualTo(Interaction.KeyPress("back"))
    assertThat(mapper.map("Open Quick Actions now")).isNull()
    assertThat(mapper.mapAll(listOf("Open Quick Actions", "do something unmapped"))).isNull()
  }

  @Test
  fun `iOS maps the key action and leaves rejection to execution`() {
    assertThat(InteractionMapper.forPlatform(Platform.IOS, keys).map("Open Quick Actions"))
      .isEqualTo(Interaction.KeyCode(174, longPress = false))
  }

  @Test
  fun `no key actions returns the platform mapper itself`() {
    assertThat(InteractionMapper.forPlatform(Platform.ANDROID_TV, emptyList()))
      .isSameInstanceAs(InteractionMapper.forPlatform(Platform.ANDROID_TV))
  }
}
