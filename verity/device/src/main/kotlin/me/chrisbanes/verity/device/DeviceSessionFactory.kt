package me.chrisbanes.verity.device

import dadb.Dadb
import dadb.adbserver.AdbServer
import device.SimctlIOSDevice
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import maestro.Maestro
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.android.AndroidDeviceSession
import me.chrisbanes.verity.device.ios.IosDeviceSession

/**
 * Creates [DeviceSession] instances with auto-discovery and animation management.
 *
 * When [disableAnimations] is true (Android only):
 * - Reads current animation scale values via [DeviceSession.getAnimationState]
 * - Sets window_animation_scale, transition_animation_scale,
 *   animator_duration_scale to 0
 * - Restores original values when session is closed
 */
object DeviceSessionFactory {
  suspend fun connect(
    platform: Platform,
    deviceId: String? = null,
    disableAnimations: Boolean = false,
  ): DeviceSession = when (platform) {
    Platform.ANDROID_TV,
    Platform.ANDROID_MOBILE,
    -> connectAndroid(platform, deviceId, disableAnimations)

    Platform.IOS -> {
      if (disableAnimations) {
        System.err.println("Warning: disableAnimations is not supported on iOS, ignoring")
      }
      connectIos(deviceId)
    }
  }

  private suspend fun connectAndroid(
    platform: Platform,
    deviceId: String?,
    disableAnimations: Boolean,
  ): DeviceSession {
    val dadb = resolveAndroidConnection(deviceId)

    val driver = AndroidDriver(dadb)
    val maestro = Maestro.android(driver)
    val session = AndroidDeviceSession(dadb, maestro, platform)

    if (disableAnimations) {
      val state = session.getAnimationState()
      session.disableAnimations()
      return AnimationRestoringSession(session, state)
    }

    return session
  }

  internal fun resolveAndroidConnection(
    deviceId: String?,
    createWithQuery: (String) -> Dadb = { query -> AdbServer.createDadb(deviceQuery = query) },
    discover: () -> Dadb? = { Dadb.discover() },
  ): Dadb = deviceId?.let { id ->
    validateAndroidDeviceId(id)
    createWithQuery("host:transport:$id")
  }
    ?: discover()
    ?: error("No Android device found. Is ADB available?")

  internal suspend fun resolveIosDeviceId(
    deviceId: String?,
    discover: suspend () -> List<String> = ::discoverBootedIosSimulatorIds,
  ): String {
    if (deviceId != null) return deviceId

    val simulatorIds = discover()
    return when (simulatorIds.size) {
      0 -> error("No iOS simulator found. Provide a device ID or boot a simulator.")

      1 -> simulatorIds.single()

      else -> error(
        "Multiple booted iOS simulators found: ${simulatorIds.joinToString(", ")}. " +
          "Provide a device ID.",
      )
    }
  }

  private suspend fun connectIos(deviceId: String?): DeviceSession {
    val iosDevice = SimctlIOSDevice(
      resolveIosDeviceId(deviceId),
    )
    val driver = IOSDriver(iosDevice)
    val maestro = Maestro.ios(driver)
    return IosDeviceSession(maestro, iosDevice)
  }

  private suspend fun discoverBootedIosSimulatorIds(): List<String> = withContext(Dispatchers.IO) {
    val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "booted", "-j")
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "xcrun simctl list failed: $output" }

    val root = Json.parseToJsonElement(output).jsonObject
    val devices = root["devices"]?.jsonObject ?: return@withContext emptyList()
    devices.values
      .flatMap { runtimeDevices ->
        runtimeDevices.jsonArray.mapNotNull { device ->
          device.jsonObject["udid"]?.jsonPrimitive?.contentOrNull
        }
      }
      .distinct()
  }

  private fun validateAndroidDeviceId(deviceId: String) {
    val ipv4WithOptionalPort = Regex("""^\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?$""")
    require(!ipv4WithOptionalPort.matches(deviceId)) {
      "Invalid Android device ID '$deviceId'. Expected an ADB serial (for example, " +
        "emulator-5554), not an IP address."
    }
  }
}

/**
 * Wrapper that restores Android animation state when the session is closed.
 */
private class AnimationRestoringSession(
  private val delegate: AndroidDeviceSession,
  private val savedState: DeviceSession.AnimationState?,
) : DeviceSession by delegate {
  override fun close() {
    try {
      if (savedState != null) {
        runBlocking {
          withTimeout(5.seconds) {
            delegate.restoreAnimationState(savedState)
          }
        }
      }
    } finally {
      delegate.close()
    }
  }
}
