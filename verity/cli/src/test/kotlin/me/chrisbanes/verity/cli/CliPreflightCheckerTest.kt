package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.device.preflight.DevicePreflightChecker

class CliPreflightCheckerTest {
  @Test
  fun `reports missing provider credential`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    val checker = CliPreflightChecker(
      environment = { null },
      devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
    )

    val result = checker.check(
      request = CliPreflightRequest(
        cliProvider = "anthropic",
        cliNavigatorModel = null,
        cliInspectorModel = null,
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = null,
      ),
      config = VerityConfig(),
    )

    assertThat(result.report.passed).isFalse()
    assertThat(result.report.issues.single().code).isEqualTo(PreflightCodes.PROVIDER_CREDENTIAL_MISSING)
  }

  @Test
  fun `reports unknown provider without throwing`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    val checker = CliPreflightChecker(
      devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
    )

    val result = checker.check(
      request = CliPreflightRequest(
        cliProvider = "unknown",
        cliNavigatorModel = null,
        cliInspectorModel = null,
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = null,
      ),
      config = VerityConfig(),
    )

    assertThat(result.report.issues.single().code).isEqualTo(PreflightCodes.PROVIDER_UNKNOWN)
  }

  @Test
  fun `returns resolved provider models and credential when preflight passes`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    val checker = CliPreflightChecker(
      environment = { name -> if (name == "ANTHROPIC_API_KEY") "secret" else null },
      devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
    )

    val result = checker.check(
      request = CliPreflightRequest(
        cliProvider = "anthropic",
        cliNavigatorModel = "claude-haiku-4-5",
        cliInspectorModel = "claude-opus-4-5",
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = "emulator-5554",
      ),
      config = VerityConfig(),
    )

    assertThat(result.report.passed).isTrue()
    assertThat(result.provider).isNotNull()
    assertThat(result.apiKey).isEqualTo("secret")
    assertThat(result.navigatorModel?.id).isEqualTo("claude-haiku-4-5")
    assertThat(result.inspectorModel?.id).isEqualTo("claude-opus-4-5")
  }

  @Test
  fun `runs device preflight for selected platform`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    var receivedPlatform: Platform? = null
    var receivedDevice: String? = null
    val checker = CliPreflightChecker(
      environment = { name -> if (name == "ANTHROPIC_API_KEY") "secret" else null },
      devicePreflightChecker = DevicePreflightChecker { platform, device ->
        receivedPlatform = platform
        receivedDevice = device
        PreflightReport()
      },
    )

    checker.check(
      request = CliPreflightRequest(
        cliProvider = "anthropic",
        cliNavigatorModel = null,
        cliInspectorModel = null,
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.IOS,
        deviceId = "sim-1",
      ),
      config = VerityConfig(),
    )

    assertThat(receivedPlatform).isEqualTo(Platform.IOS)
    assertThat(receivedDevice).isEqualTo("sim-1")
  }
}
