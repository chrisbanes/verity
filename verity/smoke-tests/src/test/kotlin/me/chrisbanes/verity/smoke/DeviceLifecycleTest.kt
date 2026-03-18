package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isNotNull
import kotlin.test.Test

class DeviceLifecycleTest {
  @Test
  fun `android lifecycle creates without error`() {
    val lifecycle = DeviceLifecycle.android()
    assertThat(lifecycle).isNotNull()
  }

  @Test
  fun `ios lifecycle creates without error`() {
    val lifecycle = DeviceLifecycle.ios()
    assertThat(lifecycle).isNotNull()
  }
}
