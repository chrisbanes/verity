package me.chrisbanes.verity.device

import device.SimctlIOSDevice
import ios.LocalIOSDevice
import ios.xctest.XCTestIOSDevice
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import maestro.Maestro
import maestro.android.AndroidDeviceConnection
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.android.AndroidDeviceSession
import me.chrisbanes.verity.device.android.AnimationState
import me.chrisbanes.verity.device.android.ensureAwakeOrClose
import me.chrisbanes.verity.device.ios.BoundedIosHierarchyCapture
import me.chrisbanes.verity.device.ios.BoundedIosScreenshotCapture
import me.chrisbanes.verity.device.ios.IosDeviceSession
import me.chrisbanes.verity.device.ios.XCTestHierarchyIosDevice
import me.chrisbanes.verity.device.preflight.LocalProcessRunner
import me.chrisbanes.verity.device.preflight.isBareIpv4Address
import me.chrisbanes.verity.device.preflight.parseSimctlDevices
import util.IOSDeviceType
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.Context
import xcuitest.installer.LocalXCTestInstaller
import xcuitest.installer.XCTestInstaller

/**
 * Creates [DeviceSession] instances with auto-discovery and animation management.
 *
 * When [disableAnimations] is true (Android only):
 * - Reads current animation scale values via [AndroidDeviceSession.getAnimationState]
 * - Sets window_animation_scale, transition_animation_scale,
 *   animator_duration_scale to 0
 * - Restores original values when session is closed
 */
object DeviceSessionFactory {
  suspend fun connect(
    platform: Platform,
    deviceId: String? = null,
    disableAnimations: Boolean = false,
    onCommandStart: ((Int) -> Unit)? = null,
  ): DeviceSession = when (platform) {
    Platform.ANDROID_TV,
    Platform.ANDROID_MOBILE,
    -> connectAndroid(platform, deviceId, disableAnimations, onCommandStart)

    Platform.IOS -> {
      if (disableAnimations) {
        System.err.println("Warning: disableAnimations is not supported on iOS, ignoring")
      }
      connectIos(deviceId, onCommandStart)
    }
  }

  private suspend fun connectAndroid(
    platform: Platform,
    deviceId: String?,
    disableAnimations: Boolean,
    onCommandStart: ((Int) -> Unit)?,
  ): DeviceSession {
    val connection = resolveAndroidConnection(
      deviceId = deviceId,
      createWithId = { id -> AndroidDeviceConnection.byId(id, host = "localhost") },
      discover = { AndroidDeviceConnection.discover(host = "localhost") },
    ) ?: error("No Android device found. Is ADB available?")

    val driver = AndroidDriver(connection)
    val maestro = Maestro.android(driver)
    val session = AndroidDeviceSession(
      maestro = maestro,
      platform = platform,
      onCommandStart = onCommandStart,
      executeShell = { command -> connection.shell(command).output },
    )

    ensureAwakeOrClose(session::ensureAwake, session::close)

    if (disableAnimations) {
      val state = session.getAnimationState()
      session.disableAnimations()
      return AnimationRestoringSession(session, state)
    }

    return session
  }

  internal fun <T> resolveAndroidConnection(
    deviceId: String?,
    createWithId: (String) -> T,
    discover: () -> T?,
  ): T? = deviceId?.let { id ->
    validateAndroidDeviceId(id)
    createWithId(id)
  }
    ?: discover()

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

  private suspend fun connectIos(deviceId: String?, onCommandStart: ((Int) -> Unit)?): DeviceSession {
    val resolvedId = resolveIosDeviceId(deviceId)
    val simctlDevice = SimctlIOSDevice(resolvedId)

    val installer = LocalXCTestInstaller(
      deviceId = resolvedId,
      logsDir = File(System.getProperty("java.io.tmpdir")),
      deviceType = IOSDeviceType.SIMULATOR,
      defaultPort = 22087,
      iOSDriverConfig = LocalXCTestInstaller.IOSDriverConfig(
        prebuiltRunner = true,
        sourceDirectory = "driver-iPhoneSimulator",
        context = Context.CLI,
        snapshotKeyHonorModalViews = null,
      ),
      deviceController = simctlDevice,
    )
    val endpoint = RecordingXCTestInstaller(installer)
    val driverClient = XCTestDriverClient(endpoint)
    val xcTestDevice = XCTestIOSDevice(
      deviceId = resolvedId,
      client = driverClient,
      getInstalledApps = { emptySet() },
    )
    val iosDevice = XCTestHierarchyIosDevice(
      LocalIOSDevice(
        deviceId = resolvedId,
        xcTestDevice = xcTestDevice,
        deviceController = simctlDevice,
      ),
      xcTestDevice,
    )

    val driver = IOSDriver(iosDevice)
    val maestro = Maestro.ios(driver, openDriver = true)
    val capture = BoundedIosHierarchyCapture(endpoint = { endpoint.current() })
    val screenshot = BoundedIosScreenshotCapture(endpoint = { endpoint.current() })
    return IosDeviceSession(maestro, iosDevice, onCommandStart, capture::capture, screenshot::capture)
  }

  private suspend fun discoverBootedIosSimulatorIds(): List<String> {
    val (exitCode, output) = LocalProcessRunner.run(listOf("xcrun", "simctl", "list", "devices", "booted", "-j"))
    check(exitCode == 0) { "xcrun simctl list failed: $output" }
    return parseSimctlDevices(output).map { it.udid }.distinct()
  }

  private fun validateAndroidDeviceId(deviceId: String) {
    require(!isBareIpv4Address(deviceId)) {
      "Invalid Android device ID '$deviceId'. Expected an ADB serial from `adb devices` " +
        "(for example, emulator-5554 or 192.168.1.20:5555), not a bare IP address."
    }
  }
}

/**
 * Wrapper that restores Android animation state when the session is closed.
 */
private class AnimationRestoringSession(
  private val delegate: AndroidDeviceSession,
  private val savedState: AnimationState?,
) : DeviceSession by delegate {
  override fun close() {
    try {
      if (savedState != null) {
        runBlocking(Dispatchers.IO) {
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

/** Records only main-owned starts; observation reads never affect installer lifecycle. */
internal class RecordingXCTestInstaller(private val delegate: XCTestInstaller) : XCTestInstaller by delegate {
  @Volatile private var endpoint: XCTestClient? = null
  override fun start(): XCTestClient = delegate.start().also { endpoint = it }
  fun current(): XCTestClient = checkNotNull(endpoint) { "Main XCTest endpoint has not started" }
}
