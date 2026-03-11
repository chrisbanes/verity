package me.chrisbanes.verity.core.keymap

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test
import me.chrisbanes.verity.core.model.Platform

class PlatformKeyMapperTest {

  // Android TV tests
  @Test
  fun `android tv maps d-pad down`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    assertThat(mapper.map("press d-pad down")).isEqualTo("Remote Dpad Down")
  }

  @Test
  fun `android tv maps d-pad up`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    assertThat(mapper.map("press d-pad up")).isEqualTo("Remote Dpad Up")
  }

  @Test
  fun `android tv maps d-pad left`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    assertThat(mapper.map("press d-pad left")).isEqualTo("Remote Dpad Left")
  }

  @Test
  fun `android tv maps d-pad right`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    assertThat(mapper.map("press d-pad right")).isEqualTo("Remote Dpad Right")
  }

  @Test
  fun `android tv maps select`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    assertThat(mapper.map("press select")).isEqualTo("Remote Dpad Center")
  }

  @Test
  fun `android tv maps d-pad center`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    assertThat(mapper.map("press d-pad center")).isEqualTo("Remote Dpad Center")
  }

  @Test
  fun `android tv maps back`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    assertThat(mapper.map("press back")).isEqualTo("back")
  }

  @Test
  fun `android tv maps home`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    assertThat(mapper.map("press home")).isEqualTo("home")
  }

  @Test
  fun `android tv is case insensitive`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    assertThat(mapper.map("Press D-Pad Down")).isEqualTo("Remote Dpad Down")
  }

  @Test
  fun `android tv returns null for unknown action`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    assertThat(mapper.map("tap on the button")).isNull()
  }

  // Android Mobile tests
  @Test
  fun `android mobile maps back`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_MOBILE)
    assertThat(mapper.map("press back")).isNotNull()
  }

  // iOS tests
  @Test
  fun `ios maps home`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.IOS)
    assertThat(mapper.map("press home")).isNotNull()
  }

  // All actions mappable test
  @Test
  fun `allMappable returns true when all actions map`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    val actions = listOf("press d-pad down", "press d-pad down", "press select")
    assertThat(mapper.allMappable(actions)).isEqualTo(true)
  }

  @Test
  fun `allMappable returns false when any action is unmappable`() {
    val mapper = PlatformKeyMapper.forPlatform(Platform.ANDROID_TV)
    val actions = listOf("press d-pad down", "navigate to settings")
    assertThat(mapper.allMappable(actions)).isEqualTo(false)
  }
}
