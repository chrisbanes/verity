package me.chrisbanes.verity.device.preflight

import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PreflightReport

fun interface DevicePreflightChecker {
  suspend fun check(platform: Platform, deviceId: String?): PreflightReport
}

class PlatformDevicePreflightChecker(
  private val androidChecker: AndroidPreflightChecker = AndroidPreflightChecker(),
  private val iosChecker: IosPreflightChecker = IosPreflightChecker(),
) : DevicePreflightChecker {
  override suspend fun check(platform: Platform, deviceId: String?): PreflightReport = when (platform) {
    Platform.ANDROID_TV,
    Platform.ANDROID_MOBILE,
    -> androidChecker.check(deviceId)

    Platform.IOS -> iosChecker.check(deviceId)
  }
}
