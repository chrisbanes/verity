package me.chrisbanes.verity.device.preflight

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.device.DeviceSessionFactory

private val BOOTED_SIM_1 =
  """{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-18-5":[{"udid":"sim-1","state":"Booted"}]}}"""

class IosPreflightCheckerTest {
  @Test
  fun `passes with one booted simulator`() = runTest {
    val checker = IosPreflightChecker(
      processRunner = FakeIosProcessRunner(ProcessResult(0, BOOTED_SIM_1)),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.passed).isTrue()
    assertThat(report.deviceId).isEqualTo("sim-1")
  }

  @Test
  fun `resolved device id lets session creation skip simulator discovery`() = runTest {
    val runner = FakeIosProcessRunner(ProcessResult(0, BOOTED_SIM_1))

    val report = IosPreflightChecker(runner).check(deviceId = null)
    val sessionDeviceId = DeviceSessionFactory.resolveIosDeviceId(report.deviceId) { error("discovery must not run") }

    assertThat(sessionDeviceId).isEqualTo("sim-1")
    assertThat(runner.commands).isEqualTo(listOf(listOf("xcrun", "simctl", "list", "devices", "-j")))
  }

  @Test
  fun `returns the explicit booted simulator`() = runTest {
    val checker = IosPreflightChecker(FakeIosProcessRunner(ProcessResult(0, BOOTED_SIM_1)))

    assertThat(checker.check(deviceId = "sim-1").deviceId).isEqualTo("sim-1")
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
    assertThat(report.deviceId).isNull()
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
    assertThat(report.deviceId).isNull()
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
    assertThat(report.deviceId).isNull()
  }
}

private class FakeIosProcessRunner(
  private val result: ProcessResult,
) : ProcessRunner {
  val commands = mutableListOf<List<String>>()

  override suspend fun run(command: List<String>): ProcessResult {
    commands += command
    assertThat(command).isEqualTo(listOf("xcrun", "simctl", "list", "devices", "-j"))
    return result
  }
}
