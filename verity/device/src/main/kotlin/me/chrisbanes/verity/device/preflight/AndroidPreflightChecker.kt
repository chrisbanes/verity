package me.chrisbanes.verity.device.preflight

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.core.preflight.PreflightIssue
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.core.preflight.PreflightSeverity

class AndroidPreflightChecker(
  private val processRunner: ProcessRunner = LocalProcessRunner,
) {
  suspend fun check(deviceId: String?): PreflightReport {
    if (deviceId != null && isIpAddress(deviceId)) {
      return PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.ANDROID_DEVICE_ID_INVALID,
            severity = PreflightSeverity.ERROR,
            message = "Invalid Android device ID '$deviceId'.",
            remediation = "Pass an ADB serial such as emulator-5554 instead of an IP address.",
            details = mapOf("device" to deviceId),
          ),
        ),
      )
    }

    val adb = runAdb(listOf("adb", "version"))
    if (adb == null || adb.exitCode == 127) {
      return PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.ANDROID_ADB_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "ADB is not available.",
            remediation = "Install Android platform-tools and ensure adb is on PATH.",
          ),
        ),
      )
    }
    if (adb.exitCode != 0) {
      return PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.ANDROID_ADB_FAILED,
            severity = PreflightSeverity.ERROR,
            message = "ADB failed to run.",
            remediation = "Fix the adb installation and verify `adb version` succeeds.",
            details = mapOf("output" to adb.output.trim()),
          ),
        ),
      )
    }

    return if (deviceId != null) {
      checkExplicitDevice(deviceId)
    } else {
      checkAutoDiscoveredDevice()
    }
  }

  private suspend fun checkExplicitDevice(deviceId: String): PreflightReport {
    val state = runAdb(listOf("adb", "-s", deviceId, "get-state"))
    return if (state?.exitCode == 0 && state.output.trim() == "device") {
      PreflightReport()
    } else {
      PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.ANDROID_DEVICE_UNAVAILABLE,
            severity = PreflightSeverity.ERROR,
            message = "Android device '$deviceId' is not available.",
            remediation = "Start or connect the device, then verify `adb -s $deviceId get-state` returns device.",
            details = mapOf("device" to deviceId, "output" to (state?.output?.trim().orEmpty())),
          ),
        ),
      )
    }
  }

  private suspend fun checkAutoDiscoveredDevice(): PreflightReport {
    val devices = runAdb(listOf("adb", "devices"))
    val hasDevice = devices?.exitCode == 0 && devices.output
      .lineSequence()
      .drop(1)
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .any { line -> line.split(Regex("\\s+")).getOrNull(1) == "device" }

    return if (hasDevice) {
      PreflightReport()
    } else {
      PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.ANDROID_DEVICE_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "No Android device was found.",
            remediation = "Start an emulator, connect a device, or pass --device <serial>.",
            details = mapOf("output" to (devices?.output?.trim().orEmpty())),
          ),
        ),
      )
    }
  }

  private suspend fun runAdb(command: List<String>): ProcessResult? =
    try {
      processRunner.run(command)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      null
    }

  private fun isIpAddress(value: String): Boolean =
    Regex("""^\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?$""").matches(value)
}
