package me.chrisbanes.verity.device

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.Maestro
import maestro.Platform as MaestroPlatform
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.android.AndroidDeviceSession
import me.chrisbanes.verity.device.ios.IosDeviceSession

class DeviceSessionProductionReadyTest {

  @Test
  fun `android executeFlow returns success for valid flow`() = runTest {
    val session = AndroidDeviceSession(
      dadb = FakeDadb(),
      maestro = Maestro(FakeDriver()),
      platform = Platform.ANDROID_MOBILE,
    )

    val result = session.executeFlow(
      """
      appId: com.example.app
      ---
      - launchApp
      """.trimIndent(),
    )

    assertThat(result).isEqualTo(FlowResult(success = true))
  }

  @Test
  fun `android executeFlow returns failure output for invalid flow`() = runTest {
    val session = AndroidDeviceSession(
      dadb = FakeDadb(),
      maestro = Maestro(FakeDriver()),
      platform = Platform.ANDROID_MOBILE,
    )

    val result = session.executeFlow("- launchApp")

    assertThat(result.success).isEqualTo(false)
    assertThat(result.output).contains("Config Section Required")
  }

  @Test
  fun `android executeFlow returns failure output for runtime errors`() = runTest {
    val session = AndroidDeviceSession(
      dadb = FakeDadb(),
      maestro = Maestro(ThrowingDriver()),
      platform = Platform.ANDROID_MOBILE,
    )

    val result = session.executeFlow(
      """
      appId: com.example.app
      ---
      - launchApp
      """.trimIndent(),
    )

    assertThat(result.success).isEqualTo(false)
    assertThat(result.output).contains("Unable to launch app")
  }

  @Test
  fun `ios executeFlow returns success for valid flow`() = runTest {
    val session = IosDeviceSession(
      maestro = Maestro(FakeDriver()),
      iosDevice = FakeIosDevice(),
    )

    val result = session.executeFlow(
      """
      appId: com.example.app
      ---
      - launchApp
      """.trimIndent(),
    )

    assertThat(result).isEqualTo(FlowResult(success = true))
  }

  @Test
  fun `resolveIosDeviceId returns explicit id when provided`() {
    assertThat(DeviceSessionFactory.resolveIosDeviceId("sim-123") { error("unused") })
      .isEqualTo("sim-123")
  }

  @Test
  fun `resolveIosDeviceId falls back to discovered simulator id`() {
    assertThat(DeviceSessionFactory.resolveIosDeviceId(null) { "booted-sim" })
      .isEqualTo("booted-sim")
  }

  @Test
  fun `resolveIosDeviceId fails when discovery yields nothing`() {
    assertFailure {
      DeviceSessionFactory.resolveIosDeviceId(null) { null }
    }.messageContains("No iOS simulator found")
  }

  @Test
  fun `resolveAndroidConnection uses adb server transport query for explicit serial`() {
    val expected = FakeDadb()
    val dadb = DeviceSessionFactory.resolveAndroidConnection(
      deviceId = "emulator-5554",
      createWithQuery = { query ->
        assertThat(query).isEqualTo("host:transport:emulator-5554")
        expected
      },
      discover = { error("unused") },
    )

    assertThat(dadb).isEqualTo(expected)
  }

  @Test
  fun `resolveAndroidConnection falls back to discovery when serial omitted`() {
    val discovered = FakeDadb()

    val dadb = DeviceSessionFactory.resolveAndroidConnection(
      deviceId = null,
      createWithQuery = { error("unused") },
      discover = { discovered },
    )

    assertThat(dadb).isEqualTo(discovered)
  }

  private class FakeDadb : dadb.Dadb {
    override fun open(destination: String): dadb.AdbStream = throw UnsupportedOperationException("unused in test")

    override fun supportsFeature(feature: String): Boolean = false

    override fun close() = Unit
  }

  private class FakeIosDevice : device.IOSDevice {
    override val deviceId: String = "sim-123"
    override fun open() = Unit
    override fun deviceInfo(): xcuitest.api.DeviceInfo {
      error("unused in test")
    }

    override fun viewHierarchy(excludeKeyboardElements: Boolean): hierarchy.ViewHierarchy {
      error("unused in test")
    }

    override fun tap(x: Int, y: Int) = Unit
    override fun longPress(x: Int, y: Int, durationMs: Long) = Unit
    override fun scroll(xStart: Double, yStart: Double, xEnd: Double, yEnd: Double, duration: Double) = Unit
    override fun input(text: String) = Unit
    override fun install(stream: java.io.InputStream) = Unit
    override fun uninstall(id: String) = Unit
    override fun clearAppState(id: String) = Unit
    override fun clearKeychain(): com.github.michaelbull.result.Result<Unit, Throwable> {
      error("unused in test")
    }

    override fun launch(id: String, launchArguments: Map<String, Any>) = Unit
    override fun stop(id: String) = Unit
    override fun isKeyboardVisible(): Boolean = false
    override fun openLink(link: String): com.github.michaelbull.result.Result<Unit, Throwable> {
      error("unused in test")
    }

    override fun takeScreenshot(out: okio.Sink, compressed: Boolean) = Unit
    override fun startScreenRecording(out: okio.Sink): com.github.michaelbull.result.Result<device.IOSScreenRecording, Throwable> {
      error("unused in test")
    }

    override fun addMedia(path: String) = Unit
    override fun setLocation(latitude: Double, longitude: Double): com.github.michaelbull.result.Result<Unit, Throwable> {
      error("unused in test")
    }

    override fun setOrientation(orientation: String) = Unit
    override fun isShutdown(): Boolean = false
    override fun isScreenStatic(): Boolean = true
    override fun setPermissions(id: String, permissions: Map<String, String>) = Unit
    override fun eraseText(charactersToErase: Int) = Unit
    override fun pressKey(name: String) = Unit
    override fun pressButton(name: String) = Unit
    override fun close() = Unit
  }

  private open class FakeDriver : Driver {
    override fun name(): String = "fake"
    override fun open() = Unit
    override fun close() = Unit
    override fun deviceInfo(): DeviceInfo = DeviceInfo(MaestroPlatform.ANDROID, 1080, 1920, 1080, 1920)
    override fun launchApp(appId: String, launchArguments: Map<String, Any>) = Unit
    override fun stopApp(appId: String) = Unit
    override fun killApp(appId: String) = Unit
    override fun clearAppState(appId: String) = Unit
    override fun clearKeychain() = Unit
    override fun tap(point: Point) = Unit
    override fun longPress(point: Point) = Unit
    override fun pressKey(code: KeyCode) = Unit
    override fun contentDescriptor(excludeKeyboardElements: Boolean): maestro.TreeNode {
      error("unused in test")
    }

    override fun scrollVertical() = Unit
    override fun isKeyboardVisible(): Boolean = false
    override fun swipe(start: Point, end: Point, durationMs: Long) = Unit
    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) = Unit
    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) = Unit
    override fun backPress() = Unit
    override fun inputText(text: String) = Unit
    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) = Unit
    override fun hideKeyboard() = Unit
    override fun takeScreenshot(out: okio.Sink, compressed: Boolean) = Unit
    override fun startScreenRecording(out: okio.Sink): ScreenRecording = object : ScreenRecording {
      override fun close() = Unit
    }
    override fun setLocation(latitude: Double, longitude: Double) = Unit
    override fun setOrientation(orientation: maestro.DeviceOrientation) = Unit
    override fun eraseText(charactersToErase: Int) = Unit
    override fun setProxy(host: String, port: Int) = Unit
    override fun resetProxy() = Unit
    override fun isShutdown(): Boolean = false
    override fun isUnicodeInputSupported(): Boolean = true
    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean = true
    override fun waitForAppToSettle(
      initialHierarchy: maestro.ViewHierarchy?,
      appId: String?,
      timeoutMs: Int?,
    ): maestro.ViewHierarchy? = null

    override fun capabilities(): List<maestro.Capability> = emptyList()
    override fun setPermissions(appId: String, permissions: Map<String, String>) = Unit
    override fun addMedia(mediaFiles: List<java.io.File>) = Unit
    override fun isAirplaneModeEnabled(): Boolean = false
    override fun setAirplaneMode(enabled: Boolean) = Unit
  }

  private class ThrowingDriver : FakeDriver() {
    override fun stopApp(appId: String) {
      error("boom")
    }
  }
}
