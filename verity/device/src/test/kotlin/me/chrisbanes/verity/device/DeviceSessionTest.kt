package me.chrisbanes.verity.device

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import maestro.Driver
import maestro.Maestro
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.android.AndroidDeviceSession
import me.chrisbanes.verity.device.android.AnimationState

class DeviceSessionTest {
  @Test
  fun `legacy session supports named short overload and rejects Android capabilities`() = runTest {
    val session = FakeDeviceSession(platform = Platform.IOS)
    session.pressKey("return", false)
    assertThat(session.pressedKeys).isEqualTo(listOf("return"))
    assertFailure { session.pressKey("return", true) }.messageContains("Long key presses")
    assertFailure { session.pressKey(174, false) }.messageContains("Raw Android keycodes")
    assertThat(session.pressedKeys).isEqualTo(listOf("return"))
    assertThat(session.supportsRawKeycodes).isFalse()
    assertThat(session.supportsLongPress).isFalse()
    val android = FakeDeviceSession(platform = Platform.ANDROID_TV)
    assertThat(android.supportsRawKeycodes).isTrue()
    assertThat(android.supportsLongPress).isTrue()
  }

  @Test
  fun `animation state accepts integer scale values`() {
    val state = AnimationState("1", "0", "2")
    assertThat(state.windowScale).isEqualTo("1")
    assertThat(state.transitionScale).isEqualTo("0")
    assertThat(state.animatorScale).isEqualTo("2")
  }

  @Test
  fun `animation state accepts decimal scale values`() {
    val state = AnimationState("1.0", "0.5", "1.5")
    assertThat(state.windowScale).isEqualTo("1.0")
  }

  @Test
  fun `animation state rejects shell injection in windowScale`() {
    assertFailure {
      AnimationState("0; rm -rf /", "1.0", "1.0")
    }.messageContains("Invalid window animation scale")
  }

  @Test
  fun `animation state rejects shell injection in transitionScale`() {
    assertFailure {
      AnimationState("1.0", "$(evil)", "1.0")
    }.messageContains("Invalid transition animation scale")
  }

  @Test
  fun `animation state rejects shell injection in animatorScale`() {
    assertFailure {
      AnimationState("1.0", "1.0", "abc")
    }.messageContains("Invalid animator duration scale")
  }

  @Test
  fun `animation state rejects empty values`() {
    assertFailure {
      AnimationState("", "1.0", "1.0")
    }.messageContains("Invalid window animation scale")
  }

  @Test
  fun `animation state accepts scales the device has never set`() {
    val state = AnimationState("1.0", "null", "null")
    assertThat(state.animatorScale).isEqualTo(AnimationState.UNSET)
  }

  @Test
  fun `restoring animation state deletes scales that were never set`() = runTest {
    val commands = mutableListOf<String>()
    val unusedDriver = Proxy.newProxyInstance(Driver::class.java.classLoader, arrayOf(Driver::class.java)) { _, _, _ ->
      error("Driver should not be used")
    } as Driver
    val session = AndroidDeviceSession(Maestro(unusedDriver), Platform.ANDROID_TV) {
      commands += it
      ""
    }

    session.restoreAnimationState(AnimationState("1.0", "0.5", "null"))

    assertThat(commands).isEqualTo(
      listOf(
        "settings put global window_animation_scale 1.0",
        "settings put global transition_animation_scale 0.5",
        "settings delete global animator_duration_scale",
      ),
    )
  }
}
