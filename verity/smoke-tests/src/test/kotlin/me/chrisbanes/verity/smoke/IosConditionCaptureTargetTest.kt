package me.chrisbanes.verity.smoke

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IosConditionCaptureTargetTest {
  @Test
  fun `configured UDID selects named CI simulator without matching its display name`() {
    val selected = selectConditionCaptureIosTarget(devices, "owned", "iPhone 17", "27")
    assertThat(selected["udid"]!!.jsonPrimitive.content).isEqualTo("owned")
  }

  @Test
  fun `action-selected UDID needs no model or runtime pin and cannot fall back`() {
    val selected = selectConditionCaptureIosTarget(devices, "action-owned", null, null)
    assertThat(selected["udid"]!!.jsonPrimitive.content).isEqualTo("action-owned")
    for (target in listOf("missing", null)) {
      assertFailure { selectConditionCaptureIosTarget(devices, target, null, null) }
        .messageContains("Expected one configured job-owned simulator")
    }
  }

  @Test
  fun `model selection remains available when no UDID is configured`() {
    val selected = selectConditionCaptureIosTarget(devices, null, "iPhone 17", "27")
    assertThat(selected["udid"]!!.jsonPrimitive.content).isEqualTo("default")
  }

  @Test
  fun `wrong target runtime and shutdown state cannot substitute another booted simulator`() {
    for ((target, runtime) in listOf("missing" to "27", "owned" to "26", "owned" to "2", "shutdown" to "27")) {
      assertFailure { selectConditionCaptureIosTarget(devices, target, "iPhone 17", runtime) }
        .messageContains("Expected one configured job-owned simulator")
    }
  }

  private val devices = Json.parseToJsonElement(
    """
    {
      "com.apple.CoreSimulator.SimRuntime.iOS-27-0": [
        {"name": "Verity-packaged-run-attempt", "udid": "owned", "state": "Booted"},
        {"name": "iPhone 17", "udid": "default", "state": "Booted"},
        {"name": "iPhone 17", "udid": "shutdown", "state": "Shutdown"}
      ],
      "com.apple.CoreSimulator.SimRuntime.iOS-26-5": [
        {"name": "iPhone 17", "udid": "action-owned", "state": "Booted"}
      ]
    }
    """.trimIndent(),
  ).jsonObject
}
