package me.chrisbanes.verity.device.preflight

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.core.preflight.PreflightIssue
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.core.preflight.PreflightSeverity

class IosPreflightChecker(
  private val processRunner: ProcessRunner = LocalProcessRunner,
) {
  suspend fun check(deviceId: String?): PreflightReport {
    val result = try {
      processRunner.run(listOf("xcrun", "simctl", "list", "devices", "-j"))
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      return PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.IOS_XCRUN_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "xcrun is not available.",
            remediation = "Install Xcode command line tools and ensure xcrun is on PATH.",
          ),
        ),
      )
    }

    if (result.exitCode != 0) {
      return PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.IOS_SIMCTL_FAILED,
            severity = PreflightSeverity.ERROR,
            message = "xcrun simctl failed.",
            remediation = "Fix the Xcode command line tools setup and verify `xcrun simctl list devices -j` succeeds.",
            details = mapOf("output" to result.output.trim()),
          ),
        ),
      )
    }

    val devices = parseDevices(result.output)
    val booted = devices.filter { it.state == "Booted" }

    return if (deviceId != null) {
      val selected = devices.firstOrNull { it.udid == deviceId }
      if (selected?.state == "Booted") {
        PreflightReport()
      } else {
        PreflightReport(
          listOf(
            PreflightIssue(
              code = PreflightCodes.IOS_DEVICE_NOT_BOOTED,
              severity = PreflightSeverity.ERROR,
              message = "iOS device '$deviceId' is not booted.",
              remediation = "Boot that simulator or pass the UDID of a booted simulator.",
              details = mapOf("device" to deviceId, "state" to (selected?.state ?: "missing")),
            ),
          ),
        )
      }
    } else {
      when (booted.size) {
        0 -> PreflightReport(
          listOf(
            PreflightIssue(
              code = PreflightCodes.IOS_SIMULATOR_NONE,
              severity = PreflightSeverity.ERROR,
              message = "No booted iOS simulator was found.",
              remediation = "Boot a simulator or pass --device <udid>.",
            ),
          ),
        )

        1 -> PreflightReport()

        else -> PreflightReport(
          listOf(
            PreflightIssue(
              code = PreflightCodes.IOS_SIMULATOR_MULTIPLE,
              severity = PreflightSeverity.ERROR,
              message = "Multiple booted iOS simulators were found.",
              remediation = "Pass --device <udid> to choose one simulator.",
              details = mapOf("devices" to booted.joinToString(", ") { it.udid }),
            ),
          ),
        )
      }
    }
  }

  private fun parseDevices(json: String): List<IosDeviceState> {
    val root = Json.parseToJsonElement(json).jsonObject
    val devices = root["devices"]?.jsonObject ?: return emptyList()
    return devices.values.flatMap { runtimeDevices ->
      runtimeDevices.jsonArray.mapNotNull { device ->
        val obj = device.jsonObject
        val udid = obj["udid"]?.jsonPrimitive?.contentOrNull
        val state = obj["state"]?.jsonPrimitive?.contentOrNull
        if (udid != null && state != null) IosDeviceState(udid, state) else null
      }
    }
  }

  private data class IosDeviceState(
    val udid: String,
    val state: String,
  )
}
