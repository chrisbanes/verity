package me.chrisbanes.verity.device.preflight

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.preflight.PreflightCodes

class AndroidPreflightCheckerTest {
  @Test
  fun `passes when adb sees a connected device`() = runTest {
    val checker = AndroidPreflightChecker(
      processRunner = FakeProcessRunner(
        mapOf(
          listOf("adb", "version") to ProcessResult(0, "Android Debug Bridge version 1.0.41"),
          listOf("adb", "devices") to ProcessResult(0, "List of devices attached\nemulator-5554\tdevice\n"),
        ),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.passed).isTrue()
  }

  @Test
  fun `reports missing adb`() = runTest {
    val checker = AndroidPreflightChecker(
      processRunner = FakeProcessRunner(
        mapOf(listOf("adb", "version") to ProcessResult(127, "adb: command not found")),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.ANDROID_ADB_MISSING)
  }

  @Test
  fun `reports no auto discovered devices`() = runTest {
    val checker = AndroidPreflightChecker(
      processRunner = FakeProcessRunner(
        mapOf(
          listOf("adb", "version") to ProcessResult(0, "ok"),
          listOf("adb", "devices") to ProcessResult(0, "List of devices attached\n\n"),
        ),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.ANDROID_DEVICE_MISSING)
  }

  @Test
  fun `rejects ip address as explicit android device id`() = runTest {
    val checker = AndroidPreflightChecker(processRunner = FakeProcessRunner(emptyMap()))

    val report = checker.check(deviceId = "192.168.1.20")

    val issue = report.issues.single()
    assertThat(issue.code).isEqualTo(PreflightCodes.ANDROID_DEVICE_ID_INVALID)
    assertThat(issue.remediation.orEmpty()).contains("adb devices")
    assertThat(issue.remediation.orEmpty()).contains("192.168.1.20:5555")
  }

  @Test
  fun `accepts wireless ip port serial as explicit android device id`() = runTest {
    val checker = AndroidPreflightChecker(
      processRunner = FakeProcessRunner(
        mapOf(
          listOf("adb", "version") to ProcessResult(0, "ok"),
          listOf("adb", "-s", "192.168.1.20:5555", "get-state") to ProcessResult(0, "device\n"),
        ),
      ),
    )

    val report = checker.check(deviceId = "192.168.1.20:5555")

    assertThat(report.passed).isTrue()
  }

  @Test
  fun `reports unavailable wireless ip port serial as device unavailable`() = runTest {
    val checker = AndroidPreflightChecker(
      processRunner = FakeProcessRunner(
        mapOf(
          listOf("adb", "version") to ProcessResult(0, "ok"),
          listOf("adb", "-s", "192.168.1.20:5555", "get-state") to
            ProcessResult(1, "error: device '192.168.1.20:5555' not found"),
        ),
      ),
    )

    val report = checker.check(deviceId = "192.168.1.20:5555")

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.ANDROID_DEVICE_UNAVAILABLE)
  }
}

private class FakeProcessRunner(
  private val results: Map<List<String>, ProcessResult>,
) : ProcessRunner {
  override suspend fun run(command: List<String>): ProcessResult = results[command] ?: error("Unexpected command: $command")
}
