package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.model.Platform

class JourneyLoadTest {
  @Test
  fun `android settings journey loads`() {
    val url = javaClass.classLoader.getResource("android-settings.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))
    assertThat(journey.name).isEqualTo("Android Settings smoke")
    assertThat(journey.platform).isEqualTo(Platform.ANDROID_TV)
  }

  @Test
  fun `ios settings journey loads`() {
    val url = javaClass.classLoader.getResource("ios-settings.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))
    assertThat(journey.name).isEqualTo("iOS Settings smoke")
    assertThat(journey.platform).isEqualTo(Platform.IOS)
  }
}
