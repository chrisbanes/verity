package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import me.chrisbanes.verity.core.interaction.InteractionMapper
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.journey.JourneySegmenter
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Platform

class JourneyLoadTest {
  @Test
  fun `android settings journey loads`() {
    val url = javaClass.classLoader.getResource("android-settings.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))
    assertThat(journey.name).isEqualTo("Android Settings smoke")
    assertThat(journey.platform).isEqualTo(Platform.ANDROID_MOBILE)
  }

  @Test
  fun `ios settings journey loads`() {
    val url = javaClass.classLoader.getResource("ios-settings.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))
    assertThat(journey.name).isEqualTo("iOS Settings smoke")
    assertThat(journey.platform).isEqualTo(Platform.IOS)
  }

  @Test
  fun `android settings scroll journey loads`() {
    val url = javaClass.classLoader.getResource("android-settings-scroll.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))
    assertThat(journey.name).isEqualTo("Android Settings scroll smoke")
    assertThat(journey.platform).isEqualTo(Platform.ANDROID_MOBILE)
  }

  @Test
  fun `ios settings scroll journey loads`() {
    val url = javaClass.classLoader.getResource("ios-settings-scroll.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))
    assertThat(journey.name).isEqualTo("iOS Settings scroll smoke")
    assertThat(journey.platform).isEqualTo(Platform.IOS)
  }

  @Test
  fun `live iOS journey has one unmapped action group and one tree assertion`() {
    val url = javaClass.classLoader.getResource("packaged-live-ios.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))
    assertThat(journey.name).isEqualTo("Shrunk packaged ChatGPT journey")
    assertThat(journey.platform).isEqualTo(Platform.IOS)
    val segment = JourneySegmenter.segment(journey.steps).single()
    assertThat(InteractionMapper.forPlatform(Platform.IOS).allMappable(segment.actions.map { it.instruction })).isEqualTo(false)
    assertThat(segment.assertion?.mode).isEqualTo(AssertMode.TREE)
  }
}
