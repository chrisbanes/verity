package me.chrisbanes.verity.smoke

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import me.chrisbanes.verity.device.preflight.ProcessResult
import me.chrisbanes.verity.device.preflight.ProcessRunner

class IosIdentityQueryTest {
  @Test
  fun `slow query gets more than five seconds and preserves the owned target`() = runTest {
    val devices = queryIosConditionCaptureDevices(
      ProcessRunner { command ->
        assertThat(command).isEqualTo(listOf("xcrun", "simctl", "list", "devices", "booted", "--json"))
        delay(6.seconds)
        ProcessResult(0, response)
      },
    )
    val selected = selectConditionCaptureIosTarget(devices, "owned", null, null)
    assertThat(selected["udid"]!!.jsonPrimitive.content).isEqualTo("owned")
    assertFailure { selectConditionCaptureIosTarget(devices, "missing", null, null) }
      .messageContains("Expected one configured job-owned simulator")
  }

  @Test
  fun `timed out query is joined before retry`() = runTest {
    var calls = 0
    var active = false
    queryIosConditionCaptureDevices(
      ProcessRunner {
        assertThat(active).isEqualTo(false)
        calls++
        active = true
        try {
          if (calls == 1) delay(60.seconds)
          ProcessResult(0, response)
        } finally {
          active = false
        }
      },
    )
    assertThat(calls).isEqualTo(2)
    assertThat(active).isEqualTo(false)
  }

  @Test
  fun `persistent timeout exhausts the bounded attempts`() = runTest {
    var calls = 0
    var completed = 0
    assertFailure {
      queryIosConditionCaptureDevices(
        ProcessRunner {
          calls++
          try {
            delay(60.seconds)
            ProcessResult(0, response)
          } finally {
            completed++
          }
        },
      )
    }.messageContains("timed out after 3 attempts")
    assertThat(calls).isEqualTo(3)
    assertThat(completed).isEqualTo(calls)
  }

  @Test
  fun `command failures and caller cancellation are not retried`() = runTest {
    var calls = 0
    assertFailure {
      queryIosConditionCaptureDevices(
        ProcessRunner {
          calls++
          ProcessResult(1, "simctl failed")
        },
      )
    }.messageContains("simctl failed")
    assertThat(calls).isEqualTo(1)
    val cancellation = CancellationException("caller cancelled")
    assertFailure {
      queryIosConditionCaptureDevices(
        ProcessRunner {
          calls++
          throw cancellation
        },
      )
    }.isInstanceOf<CancellationException>().messageContains("caller cancelled")
    assertThat(calls).isEqualTo(2)
  }

  private val response = """{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-26-4":[{"udid":"owned","state":"Booted"}]}}"""
}
