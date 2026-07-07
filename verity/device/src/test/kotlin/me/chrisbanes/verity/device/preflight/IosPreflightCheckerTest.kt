package me.chrisbanes.verity.device.preflight

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.preflight.PreflightCodes

class IosPreflightCheckerTest {
  @Test
  fun `passes with one booted simulator`() = runTest {
    val checker = IosPreflightChecker(
      processRunner = FakeIosProcessRunner(
        ProcessResult(
          0,
          """
          {"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-18-5":[{"udid":"sim-1","state":"Booted"}]}}
          """.trimIndent(),
        ),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.passed).isTrue()
  }

  @Test
  fun `reports no booted simulator`() = runTest {
    val checker = IosPreflightChecker(
      processRunner = FakeIosProcessRunner(
        ProcessResult(
          0,
          """
          {"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-18-5":[{"udid":"sim-1","state":"Shutdown"}]}}
          """.trimIndent(),
        ),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.IOS_SIMULATOR_NONE)
  }

  @Test
  fun `reports multiple booted simulators`() = runTest {
    val checker = IosPreflightChecker(
      processRunner = FakeIosProcessRunner(
        ProcessResult(
          0,
          """
          {"devices":{"runtime":[{"udid":"sim-1","state":"Booted"},{"udid":"sim-2","state":"Booted"}]}}
          """.trimIndent(),
        ),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.IOS_SIMULATOR_MULTIPLE)
  }

  @Test
  fun `reports explicit simulator not booted`() = runTest {
    val checker = IosPreflightChecker(
      processRunner = FakeIosProcessRunner(
        ProcessResult(
          0,
          """
          {"devices":{"runtime":[{"udid":"sim-1","state":"Shutdown"}]}}
          """.trimIndent(),
        ),
      ),
    )

    val report = checker.check(deviceId = "sim-1")

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.IOS_DEVICE_NOT_BOOTED)
  }
}

private class FakeIosProcessRunner(
  private val result: ProcessResult,
) : ProcessRunner {
  override suspend fun run(command: List<String>): ProcessResult {
    assertThat(command).isEqualTo(listOf("xcrun", "simctl", "list", "devices", "-j"))
    return result
  }
}
