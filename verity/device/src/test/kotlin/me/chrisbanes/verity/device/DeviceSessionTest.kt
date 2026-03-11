package me.chrisbanes.verity.device

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class DeviceSessionTest {
  @Test
  fun `animation state preserves values`() {
    val state = DeviceSession.AnimationState("1.0", "1.0", "1.0")
    assertThat(state.windowScale).isEqualTo("1.0")
    assertThat(state.transitionScale).isEqualTo("1.0")
    assertThat(state.animatorScale).isEqualTo("1.0")
  }

  @Test
  fun `animation state supports zero values`() {
    val state = DeviceSession.AnimationState("0", "0", "0")
    assertThat(state.windowScale).isEqualTo("0")
    assertThat(state.transitionScale).isEqualTo("0")
    assertThat(state.animatorScale).isEqualTo("0")
  }
}
