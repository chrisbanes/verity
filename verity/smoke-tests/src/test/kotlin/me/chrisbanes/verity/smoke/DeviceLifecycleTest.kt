package me.chrisbanes.verity.smoke

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.messageContains
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

  @Test
  fun `ios simulator selection matches model and runtime major`() {
    assertThat(DeviceLifecycle.selectIosSimulator(simulators, "iPhone 17", "27"))
      .isEqualTo("iphone-27")
  }

  @Test
  fun `ios simulator selection accepts a minor runtime version`() {
    assertThat(DeviceLifecycle.selectIosSimulator(simulators, "iPhone 17", "27.1"))
      .isEqualTo("iphone-27")
  }

  @Test
  fun `ios simulator selection does not match a different version prefix`() {
    assertFailure {
      DeviceLifecycle.selectIosSimulator(simulators, "iPhone 17", "2")
    }.messageContains("No iPhone simulator found")
  }

  @Test
  fun `ios simulator selection skips other platforms ipads and unavailable devices`() {
    assertThat(DeviceLifecycle.selectIosSimulator(simulators)).isEqualTo("iphone-26")
  }

  @Test
  fun `ios simulator selection fails when the requested model is absent`() {
    assertFailure {
      DeviceLifecycle.selectIosSimulator(simulators, "iPhone 18", "27")
    }.messageContains("No iPhone simulator found")
  }

  private val simulators = """
    {
      "devices": {
        "com.apple.CoreSimulator.SimRuntime.tvOS-27-0": [
          {"name": "Apple TV", "udid": "tv", "isAvailable": true}
        ],
        "com.apple.CoreSimulator.SimRuntime.iOS-26-0": [
          {"name": "iPad Pro", "udid": "ipad", "isAvailable": true},
          {"name": "iPhone 17", "udid": "unavailable", "isAvailable": false},
          {"name": "iPhone 17", "udid": "iphone-26", "isAvailable": true}
        ],
        "com.apple.CoreSimulator.SimRuntime.iOS-27-1": [
          {"name": "iPhone 17 Pro", "udid": "iphone-pro", "isAvailable": true},
          {"name": "iPhone 17", "udid": "iphone-27", "isAvailable": true}
        ]
      }
    }
  """.trimIndent()
}
