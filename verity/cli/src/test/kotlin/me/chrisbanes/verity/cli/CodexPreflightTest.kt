package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.core.preflight.PreflightIssue
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.core.preflight.PreflightSeverity
import me.chrisbanes.verity.device.preflight.DevicePreflightChecker

class CodexPreflightTest {
  private class CallerCancellation(val marker: Any) : kotlinx.coroutines.CancellationException("caller-owned")

  @Test fun `dynamic role preflight reads no API key starts no turns and closes exact children`() = runTest {
    withContext(Dispatchers.Default) {
      val fake = FakeCodexLauncher("model-success")
      val path = Files.createTempFile("verity-preflight-", ".yaml")
      var result: CliPreflightResult? = null
      var devices = 0
      try {
        result = CliPreflightChecker(
          environment = { error("ChatGPT must not read API environment") },
          codexFactory = { fake.prepare() },
          devicePreflightChecker = DevicePreflightChecker { _, _ ->
            devices++
            PreflightReport(deviceId = "sim-resolved")
          },
        ).check(request(path.toString(), "low"), VerityConfig())
        assertThat(result.report.passed).isTrue()
        assertThat(result.deviceId).isEqualTo("sim-resolved")
        assertThat(devices).isEqualTo(1)
        val frames = fake.capturedFrames()
        assertThat(frames.count { it["method"] == JsonPrimitive("thread/start") }).isEqualTo(2)
        assertThat(frames.count { it["method"] == JsonPrimitive("turn/start") }).isEqualTo(0)
        assertThat(frames.first { it["method"] == JsonPrimitive("account/read") }["params"]!!.jsonObject["refreshToken"]).isEqualTo(JsonPrimitive(false))
      } finally {
        withContext(NonCancellable) {
          result?.backend?.close()
          fake.verifyCleanup()
          fake.cleanupReceipts()
          Files.deleteIfExists(path)
        }
      }
    }
  }

  @Test fun `signed out modality model and exact effort errors discard device result and clean up`() = runTest {
    withContext(Dispatchers.Default) {
      for ((scenario, effort, model, code) in listOf(
        listOf("model-signed-out", null, null, "codex.auth"),
        listOf("model-api-account", null, null, "codex.auth"),
        listOf("model-no-modality", null, null, "codex.modality"),
        listOf("model-success", "LOW", null, "codex.effort"),
        listOf("model-success", null, "unknown", "codex.model"),
        listOf("model-text-only", null, null, "codex.modality"),
        listOf("model-no-efforts", null, null, "codex.protocol"),
        listOf("model-duplicate", null, null, "codex.protocol"),
        listOf("model-cursor-loop", null, null, "codex.protocol"),
        listOf("model-missing-cursor", null, null, "codex.protocol"),
      )) {
        val fake = FakeCodexLauncher(scenario!!)
        val path = Files.createTempFile("verity-preflight-", ".yaml")
        try {
          val result = CliPreflightChecker(
            environment = { error("API key read") },
            codexFactory = { fake.prepare() },
            devicePreflightChecker = DevicePreflightChecker { _, _ -> deviceFailed },
          )
            .check(request(path.toString(), effort).copy(cliNavigatorModel = model), VerityConfig())
          assertThat(result.report.errors.map { it.code }).isEqualTo(listOf(code))
          assertThat(result.backend).isEqualTo(null)
          assertThat(result.report.renderPlainText().contains("sk-secret")).isEqualTo(false)
        } finally {
          withContext(NonCancellable) {
            fake.verifyCleanup()
            fake.cleanupReceipts()
            Files.deleteIfExists(path)
          }
        }
      }
    }
  }

  @Test fun `navigator only preflight ignores invalid inspector settings`() = runTest {
    withContext(Dispatchers.Default) {
      val fake = FakeCodexLauncher("model-success")
      val path = Files.createTempFile("verity-preflight-", ".yaml")
      var result: CliPreflightResult? = null
      try {
        result = CliPreflightChecker(environment = { error("API key read") }, codexFactory = { fake.prepare() })
          .check(request(path.toString(), null).copy(cliInspectorModel = "unknown", cliInspectorEffort = "wrong"), VerityConfig(), false, false)
        assertThat(result.report.passed).isTrue()
        assertThat(result.inspectorModel).isEqualTo(null)
        assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("thread/start") }).isEqualTo(1)
      } finally {
        withContext(NonCancellable) {
          result?.backend?.close()
          fake.verifyCleanup()
          fake.cleanupReceipts()
          Files.deleteIfExists(path)
        }
      }
    }
  }

  @Test fun `pagination resolves exact models and later device failure closes prepared owner`() = runTest {
    withContext(Dispatchers.Default) {
      for (deviceFailure in listOf(false, true)) {
        val fake = FakeCodexLauncher("model-pagination")
        val path = Files.createTempFile("verity-preflight-", ".yaml")
        var result: CliPreflightResult? = null
        try {
          result = CliPreflightChecker(
            environment = { error("API key read") },
            codexFactory = { fake.prepare() },
            devicePreflightChecker = DevicePreflightChecker { _, _ ->
              if (!deviceFailure) PreflightReport() else deviceFailed
            },
          ).check(request(path.toString(), null), VerityConfig())
          assertThat(result.report.passed).isEqualTo(!deviceFailure)
          assertThat(result.report.errors.map { it.code }).isEqualTo(if (deviceFailure) listOf("device.failed") else emptyList())
          assertThat(fake.capturedFrames().count { it["method"] == JsonPrimitive("model/list") }).isEqualTo(2)
          if (deviceFailure) assertThat(result.backend).isEqualTo(null)
        } finally {
          withContext(NonCancellable) {
            result?.backend?.close()
            fake.verifyCleanup()
            fake.cleanupReceipts()
            Files.deleteIfExists(path)
          }
        }
      }
    }
  }

  @Test fun `API selection performs zero Codex acquisitions`() = runTest {
    var acquisitions = 0
    val path = Files.createTempFile("verity-preflight-", ".yaml")
    try {
      val result = CliPreflightChecker(environment = { "test-key" }, codexFactory = {
        acquisitions++
        error("Codex must not launch")
      }).check(request(path.toString(), null).copy(cliOpenaiAuth = "api-key"), VerityConfig(), false)
      assertThat(result.report.passed).isTrue()
      assertThat(acquisitions).isEqualTo(0)
      assertThat(result.backend).isEqualTo(null)
    } finally {
      Files.deleteIfExists(path)
    }
  }

  @Test fun `caller cancellation during later device check retains identity and closes acquired child`() = runTest {
    withContext(Dispatchers.Default) {
      val fake = FakeCodexLauncher("model-success")
      val path = Files.createTempFile("verity-preflight-", ".yaml")
      val cancellation = CallerCancellation(Any())
      try {
        val checker = CliPreflightChecker(environment = { error("API key read") }, codexFactory = { fake.prepare() }, devicePreflightChecker = DevicePreflightChecker { _, _ -> throw cancellation })
        val thrown = kotlin.test.assertFailsWith<CallerCancellation> { checker.check(request(path.toString(), null), VerityConfig()) }
        assertThat(thrown === cancellation).isTrue()
      } finally {
        withContext(NonCancellable) {
          fake.verifyCleanup()
          fake.cleanupReceipts()
          Files.deleteIfExists(path)
        }
      }
    }
  }

  @Test fun `device check overlaps Codex startup and is cancelled when Codex fails`() = runTest(timeout = 10.seconds) {
    val device = SuspendedDevice()
    val result = checkWith(device.checker) {
      device.started.await()
      throw CodexFailure(CodexFailureKind.AUTH)
    }
    assertThat(result.report.errors.map { it.code }).isEqualTo(listOf("codex.auth"))
    assertThat(device.cancelled).isTrue()
    assertThat(device.finished).isTrue()
    assertThat(result.backend).isEqualTo(null)
  }

  @Test fun `Codex startup timeout cancels the device check`() = runTest(timeout = 10.seconds) {
    val device = SuspendedDevice()
    val result = checkWith(device.checker) { awaitCancellation() }
    assertThat(result.report.errors.map { it.code }).isEqualTo(listOf("codex.startup_timeout"))
    assertThat(device.started.isCompleted).isTrue()
    assertThat(device.cancelled).isTrue()
    assertThat(device.finished).isTrue()
    assertThat(testScheduler.currentTime).isEqualTo(30_000L)
  }

  @Test fun `caller cancellation leaves no device work running`() = runTest(timeout = 10.seconds) {
    val device = SuspendedDevice()
    val codexEntered = CompletableDeferred<Unit>()
    val job = launch {
      checkWith(device.checker) {
        codexEntered.complete(Unit)
        awaitCancellation()
      }
    }
    device.started.await()
    codexEntered.await()
    job.cancelAndJoin()
    assertThat(job.isCancelled).isTrue()
    assertThat(device.cancelled).isTrue()
    assertThat(device.finished).isTrue()
  }

  @Test fun `Codex failure discards a completed failing device result`() = runTest(timeout = 10.seconds) {
    val deviceDone = CompletableDeferred<Unit>()
    val result = checkWith(DevicePreflightChecker { _, _ -> deviceFailed.also { deviceDone.complete(Unit) } }) {
      deviceDone.await()
      throw CodexFailure(CodexFailureKind.MODEL)
    }
    assertThat(result.report.errors.map { it.code }).isEqualTo(listOf("codex.model"))
  }

  @Test fun `device exception is contained when Codex fails`() = runTest(timeout = 10.seconds) {
    val deviceDone = CompletableDeferred<Unit>()
    val result = checkWith(
      DevicePreflightChecker { _, _ ->
        try {
          throw IllegalStateException("device boom")
        } finally {
          deviceDone.complete(Unit)
        }
      },
    ) {
      deviceDone.await()
      throw CodexFailure(CodexFailureKind.AUTH)
    }
    assertThat(result.report.errors.map { it.code }).isEqualTo(listOf("codex.auth"))
  }

  @Test fun `path failure starts neither device nor Codex`() = runTest(timeout = 10.seconds) {
    var devices = 0
    var acquisitions = 0
    val result = CliPreflightChecker(
      environment = { error("API key read") },
      codexFactory = {
        acquisitions++
        error("Codex must not launch")
      },
      devicePreflightChecker = DevicePreflightChecker { _, _ ->
        devices++
        PreflightReport()
      },
    ).check(request(null, null), VerityConfig())
    assertThat(result.report.errors.map { it.code }).isEqualTo(listOf(PreflightCodes.PATH_MISSING))
    assertThat(devices).isEqualTo(0)
    assertThat(acquisitions).isEqualTo(0)
  }

  /** Device check that suspends until cancelled, recording its lifecycle. */
  private class SuspendedDevice {
    val started = CompletableDeferred<Unit>()
    var cancelled = false
    var finished = false
    val checker = DevicePreflightChecker { _, _ ->
      started.complete(Unit)
      try {
        awaitCancellation()
      } catch (e: CancellationException) {
        cancelled = true
        throw e
      } finally {
        finished = true
      }
    }
  }

  private suspend fun checkWith(device: DevicePreflightChecker, codex: suspend () -> CodexModelBackend): CliPreflightResult {
    val path = withContext(Dispatchers.IO) { Files.createTempFile("verity-preflight-", ".yaml") }
    try {
      return CliPreflightChecker(environment = { error("API key read") }, codexFactory = codex, devicePreflightChecker = device)
        .check(request(path.toString(), null), VerityConfig())
    } finally {
      withContext(Dispatchers.IO + NonCancellable) { Files.deleteIfExists(path) }
    }
  }

  private val deviceFailed = PreflightReport(listOf(PreflightIssue("device.failed", PreflightSeverity.ERROR, "Device unavailable.", "Connect device.")))

  private fun request(path: String?, effort: String?) = CliPreflightRequest(cliProvider = "openai", cliOpenaiAuth = "chatgpt", cliNavigatorModel = null, cliInspectorModel = null, cliNavigatorEffort = effort, apiKey = "ignored", journeyPath = path, contextPath = null, platform = Platform.ANDROID_TV, deviceId = null)
}
