package me.chrisbanes.verity.device

import dadb.Dadb
import dadb.adbserver.AdbServer
import device.SimctlIOSDevice
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
  ): Dadb = deviceId?.let { createWithQuery("host:transport:$it") }
    ?: discover()
    ?: error("No Android device found. Is ADB available?")

  internal fun resolveIosDeviceId(
    deviceId: String?,
    discover: () -> String? = ::discoverBootedIosSimulatorId,
  ): String = deviceId ?: discover()
    ?: error("No iOS simulator found. Provide a device ID or boot a simulator.")

  private fun connectIos(deviceId: String?): DeviceSession {
    val iosDevice = SimctlIOSDevice(
      resolveIosDeviceId(deviceId),
    )
    val driver = IOSDriver(iosDevice)
    val maestro = Maestro.ios(driver)
    return IosDeviceSession(maestro, iosDevice)
  }

  private fun discoverBootedIosSimulatorId(): String? {
    val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "booted", "-j")
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "xcrun simctl list failed: $output" }

    val idRegex = Regex("\"udid\"\\s*:\\s*\"([^\"]+)\"")
    return idRegex.find(output)?.groupValues?.get(1)
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
