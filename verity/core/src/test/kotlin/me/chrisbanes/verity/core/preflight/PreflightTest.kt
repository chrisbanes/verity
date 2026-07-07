package me.chrisbanes.verity.core.preflight

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class PreflightTest {
  @Test
  fun `empty report passes`() {
    val report = PreflightReport()

    assertThat(report.passed).isTrue()
    assertThat(report.hasErrors).isFalse()
    assertThat(report.renderPlainText()).isEqualTo("Preflight passed.")
  }

  @Test
  fun `report with error fails and renders actionable message`() {
    val report = PreflightReport(
      issues = listOf(
        PreflightIssue(
          code = PreflightCodes.ANDROID_DEVICE_MISSING,
          severity = PreflightSeverity.ERROR,
          message = "No Android device was found.",
          remediation = "Start an emulator, connect a device, or pass --device <serial>.",
        ),
      ),
    )

    assertThat(report.passed).isFalse()
    assertThat(report.hasErrors).isTrue()
    assertThat(report.errors.size).isEqualTo(1)
    assertThat(report.renderPlainText()).contains("android.device.missing")
    assertThat(report.renderPlainText()).contains("Start an emulator")
  }

  @Test
  fun `combines reports preserving issue order`() {
    val first = PreflightReport(
      listOf(
        PreflightIssue(
          code = "first",
          severity = PreflightSeverity.WARNING,
          message = "First warning.",
          remediation = "Read the warning.",
        ),
      ),
    )
    val second = PreflightReport(
      listOf(
        PreflightIssue(
          code = "second",
          severity = PreflightSeverity.ERROR,
          message = "Second error.",
          remediation = "Fix the error.",
        ),
      ),
    )

    val combined = first + second

    assertThat(combined.issues.map { it.code }).isEqualTo(listOf("first", "second"))
    assertThat(combined.passed).isFalse()
  }
}
