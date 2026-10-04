package me.chrisbanes.verity.device

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.model.Platform

class DeviceSessionTest {
  @Test
  fun `legacy session supports named short overload and rejects Android capabilities`() = runTest {
    val session = FakeDeviceSession(platform = Platform.IOS)
    session.pressKey("return", false)
    assertThat(session.pressedKeys).isEqualTo(listOf("return"))
    assertFailure { session.pressKey("return", true) }.messageContains("Long key presses")
    assertFailure { session.pressKey(174, false) }.messageContains("Raw Android keycodes")
    assertThat(session.pressedKeys).isEqualTo(listOf("return"))
  }

  @Test
  fun `animation state accepts integer scale values`() {
    val state = DeviceSession.AnimationState("1", "0", "2")
    assertThat(state.windowScale).isEqualTo("1")
    assertThat(state.transitionScale).isEqualTo("0")
    assertThat(state.animatorScale).isEqualTo("2")
  }

  @Test
  fun `animation state accepts decimal scale values`() {
    val state = DeviceSession.AnimationState("1.0", "0.5", "1.5")
    assertThat(state.windowScale).isEqualTo("1.0")
  }

  @Test
  fun `animation state rejects shell injection in windowScale`() {
    assertFailure {
      DeviceSession.AnimationState("0; rm -rf /", "1.0", "1.0")
    }.messageContains("Invalid window animation scale")
  }

  @Test
  fun `animation state rejects shell injection in transitionScale`() {
    assertFailure {
      DeviceSession.AnimationState("1.0", "$(evil)", "1.0")
    }.messageContains("Invalid transition animation scale")
  }

  @Test
  fun `animation state rejects shell injection in animatorScale`() {
    assertFailure {
      DeviceSession.AnimationState("1.0", "1.0", "abc")
    }.messageContains("Invalid animator duration scale")
  }

  @Test
  fun `animation state rejects empty values`() {
    assertFailure {
      DeviceSession.AnimationState("", "1.0", "1.0")
    }.messageContains("Invalid window animation scale")
  }
}
