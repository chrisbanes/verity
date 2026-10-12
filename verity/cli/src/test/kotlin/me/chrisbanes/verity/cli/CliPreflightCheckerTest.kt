package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
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
  fun `reports unknown nested llm navigator model without throwing`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    val checker = CliPreflightChecker(
      devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
    )

    val result = checker.check(
      request = CliPreflightRequest(
        cliProvider = null,
        cliNavigatorModel = null,
        cliInspectorModel = null,
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = null,
      ),
      config = VerityConfig(
        llm = VerityLlmConfig(
          provider = "ollama",
          navigatorModel = "definitely-not-real",
        ),
      ),
      includeDevicePreflight = false,
    )

    assertThat(result.report.passed).isFalse()
    assertThat(result.report.issues.single().code).isEqualTo(PreflightCodes.PROVIDER_MODEL_UNKNOWN)
    assertThat(result.report.issues.single().message).contains("Unknown model 'definitely-not-real'")
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
        PreflightReport(deviceId = "sim-resolved")
      },
    )

    val result = checker.check(
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
    assertThat(result.deviceId).isEqualTo("sim-resolved")
  }

  @Test
  fun `can skip device preflight for dry run`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    var devicePreflightCalls = 0
    val checker = CliPreflightChecker(
      environment = { name -> if (name == "ANTHROPIC_API_KEY") "secret" else null },
      devicePreflightChecker = DevicePreflightChecker { _, _ ->
        devicePreflightCalls += 1
        PreflightReport()
      },
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
      includeDevicePreflight = false,
    )

    assertThat(result.report.passed).isTrue()
    assertThat(devicePreflightCalls).isEqualTo(0)
    assertThat(result.deviceId).isNull()
  }

  @Test
  fun `can skip inspector model validation for dry run navigator preflight`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    val checker = CliPreflightChecker(
      environment = { name -> if (name == "ANTHROPIC_API_KEY") "secret" else null },
      devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
    )

    val result = checker.check(
      request = CliPreflightRequest(
        cliProvider = "anthropic",
        cliNavigatorModel = "claude-haiku-4-5",
        cliInspectorModel = "definitely-not-real",
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = null,
      ),
      config = VerityConfig(),
      includeDevicePreflight = false,
      includeInspectorModelPreflight = false,
    )

    assertThat(result.report.passed).isTrue()
    assertThat(result.navigatorModel?.id).isEqualTo("claude-haiku-4-5")
    assertThat(result.inspectorModel).isEqualTo(null)
  }

  @Test
  fun `plain text render contains remediation for cli error`() = runTest {
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
        journeyPath = null,
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = null,
      ),
      config = VerityConfig(),
    )

    val text = result.report.renderPlainText()

    assertThat(text).contains("provider.credential.missing")
    assertThat(text).contains("Set ANTHROPIC_API_KEY or pass --api-key")
    assertThat(text).contains("Run `verity run <path.journey.yaml>`")
  }

  @Test
  fun `rejects unsupported effort before device preflight and returns role parameters`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    var devicePreflightCalls = 0
    val checker = CliPreflightChecker(
      environment = { "test-key" },
      devicePreflightChecker = DevicePreflightChecker { _, _ ->
        devicePreflightCalls += 1
        PreflightReport()
      },
    )

    val result = checker.check(
      request = CliPreflightRequest(
        cliProvider = "openai",
        cliNavigatorModel = "gpt-5.2",
        cliInspectorModel = "gpt-5.2-pro",
        cliNavigatorEffort = "xhigh",
        cliInspectorEffort = "low",
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = null,
      ),
      config = VerityConfig(),
    )

    assertThat(result.report.errors.single().code).isEqualTo(PreflightCodes.PROVIDER_EFFORT_UNSUPPORTED)
    assertThat(result.report.errors.single().details["role"]).isEqualTo("inspector")
    assertThat(result.report.renderPlainText()).contains("leave the setting unset")
    assertThat(devicePreflightCalls).isEqualTo(0)
    assertThat(result.navigatorParams.additionalProperties.orEmpty().keys).contains("reasoning_effort")
    assertThat(result.inspectorParams.additionalProperties.orEmpty().keys).isEmpty()
  }

  @Test
  fun `uses cli effort before nested config and rejects unknown backend identity`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    val checker = CliPreflightChecker(
      environment = { "test-key" },
      devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
      backendIdentity = { _, _ -> "openai-chatgpt-codex" },
    )

    val result = checker.check(
      request = CliPreflightRequest(
        cliProvider = "openai",
        cliNavigatorModel = "gpt-5.2",
        cliInspectorModel = "gpt-5.2-pro",
        cliNavigatorEffort = "xhigh",
        cliInspectorEffort = null,
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = null,
      ),
      config = VerityConfig(
        llm = VerityLlmConfig(
          navigatorEffort = "low",
          inspectorEffort = "medium",
        ),
      ),
      includeDevicePreflight = false,
    )

    assertThat(result.report.errors.map { it.code })
      .isEqualTo(listOf(PreflightCodes.PROVIDER_EFFORT_UNSUPPORTED, PreflightCodes.PROVIDER_EFFORT_UNSUPPORTED))
  }
  private fun traceRequest(journey: java.nio.file.Path, trace: java.nio.file.Path?) = CliPreflightRequest(
    cliProvider = "anthropic",
    cliNavigatorModel = null,
    cliInspectorModel = null,
    apiKey = "secret",
    journeyPath = journey.toString(),
    contextPath = null,
    platform = Platform.ANDROID_TV,
    deviceId = null,
    modelTracePath = trace?.toString(),
  )

  @Test
  fun `no model trace path opens no trace`() = runTest {
    val dir = Files.createTempDirectory("verity-trace-preflight")
    try {
      val journey = Files.createFile(dir.resolve("a.journey.yaml"))
      val result = CliPreflightChecker(devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() })
        .check(traceRequest(journey, null), VerityConfig())

      assertThat(result.report.passed).isTrue()
      assertThat(result.trace).isNull()
      assertThat(Files.list(dir).use { it.count() }).isEqualTo(1L)
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `passing preflight transfers an owner only trace`() = runTest {
    val dir = Files.createTempDirectory("verity-trace-preflight")
    try {
      val journey = Files.createFile(dir.resolve("a.journey.yaml"))
      val tracePath = dir.resolve("trace.log")
      val result = CliPreflightChecker(devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() })
        .check(traceRequest(journey, tracePath), VerityConfig())

      assertThat(result.report.passed).isTrue()
      assertThat(result.trace).isNotNull()
      result.trace?.close()
      assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(tracePath))).isEqualTo("rw-------")
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `failing preflight closes the trace it opened`() = runTest {
    val dir = Files.createTempDirectory("verity-trace-preflight")
    try {
      val journey = Files.createFile(dir.resolve("a.journey.yaml"))
      var opened: ModelTrace? = null
      val result = CliPreflightChecker(
        devicePreflightChecker = DevicePreflightChecker { _, _ ->
          PreflightReport(listOf(me.chrisbanes.verity.core.preflight.PreflightIssue("device.missing", me.chrisbanes.verity.core.preflight.PreflightSeverity.ERROR, "No device", "Connect one")))
        },
        openTrace = { path -> ModelTrace.open(path).also { opened = it } },
      ).check(traceRequest(journey, dir.resolve("trace.log")), VerityConfig())

      assertThat(result.report.passed).isFalse()
      assertThat(result.trace).isNull()
      // A closed trace ignores writes.
      opened!!.write("request", "after close")
      assertThat(Files.size(dir.resolve("trace.log"))).isEqualTo(0L)
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `symlink model trace fails setup before Codex preparation`() = runTest {
    val dir = Files.createTempDirectory("verity-trace-preflight")
    try {
      val journey = Files.createFile(dir.resolve("a.journey.yaml"))
      val link = Files.createSymbolicLink(dir.resolve("trace.log"), Files.createFile(dir.resolve("target.log")))
      var prepared = 0
      val result = CliPreflightChecker(
        environment = { null },
        devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
        codexFactory = {
          prepared++
          error("Codex must not start")
        },
      ).check(traceRequest(journey, link).copy(cliProvider = "openai", cliOpenaiAuth = "chatgpt", apiKey = null), VerityConfig())

      assertThat(result.report.passed).isFalse()
      val issue = result.report.errors.single()
      assertThat(issue.code).isEqualTo(PreflightCodes.PATH_NOT_WRITABLE)
      assertThat(issue.message).isEqualTo("Model trace file cannot be opened safely: $link")
      assertThat(result.trace).isNull()
      assertThat(prepared).isEqualTo(0)
    } finally {
      dir.toFile().deleteRecursively()
    }
  }
}
