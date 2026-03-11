package me.chrisbanes.verity.device

import dadb.Dadb
import device.SimctlIOSDevice
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

    Platform.IOS -> connectIos(deviceId)
  }

  private suspend fun connectAndroid(
    platform: Platform,
    deviceId: String?,
    disableAnimations: Boolean,
  ): DeviceSession {
    val dadb: Dadb = if (deviceId != null) {
      Dadb.create(deviceId, 5555)
    } else {
      Dadb.discover()
        ?: error("No Android device found. Is ADB available?")
    }

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

  private fun connectIos(deviceId: String?): DeviceSession {
    val iosDevice = SimctlIOSDevice(
      deviceId ?: error("iOS device ID is required"),
    )
    val driver = IOSDriver(iosDevice)
    val maestro = Maestro.ios(driver)
    return IosDeviceSession(maestro, iosDevice)
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
        // Use runBlocking since close() is not suspending
        kotlinx.coroutines.runBlocking {
          delegate.restoreAnimationState(savedState)
        }
      }
    } finally {
      delegate.close()
    }
  }
}
