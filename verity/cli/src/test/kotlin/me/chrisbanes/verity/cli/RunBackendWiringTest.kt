package me.chrisbanes.verity.cli

import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import me.chrisbanes.verity.agent.ModelBackendFailure
import me.chrisbanes.verity.agent.ModelBackendFailureKind
import me.chrisbanes.verity.agent.modelReply
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.device.FakeDeviceSession

class RunBackendWiringTest {
  private class CallerCancellation(val marker: Any) : CancellationException("caller-owned")
  private class Backend(val failure: Exception? = null, val closeFailure: Boolean = false, val failAfter: Int? = null) : ModelRequestBackend {
    val requests = mutableListOf<ModelRequest>()
    var closed = false
    override suspend fun execute(request: ModelRequest): Message.Assistant {
      requests += request
      failure?.takeIf { failAfter == null || requests.size == failAfter }?.let { throw it }
      return modelReply(if (request.promptName == "navigator") """{"actions":[{"type":"launchApp"}]}""" else """{"passed":true,"reasoning":"verified"}""")
    }
    override suspend fun close() {
      closed = true
      if (closeFailure) error("sk-close-secret")
    }
  }

  @Test fun `production normal callbacks reuse selected backend before session and close on success`() {
    val dir = createTempDirectory("verity-backend-run").toFile()
    val backend = Backend()
    val device = FakeDeviceSession()
    val session = object : me.chrisbanes.verity.device.DeviceSession by device {
      override suspend fun captureScreenshot(output: java.nio.file.Path) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { java.nio.file.Files.write(output, byteArrayOf(1)) }
      }
    }
    var prepared = false
    try {
      val file = journey(dir, "complete onboarding wizard").apply {
        appendText(
          """  - "[?tree] Ready"
  - "[?visual] Ready"
""",
        )
      }
      val command = RunCommand(preflightChecker = { _, _, _, _ ->
        prepared = true
        preflight(backend)
      }, sessionFactory = { _, _, _ ->
        check(prepared)
        session
      }, clientFactory = { _, _ -> error("ChatGPT must not create API client") })
      val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${dir.path}/output run ${file.path}")
      assertThat(result.statusCode).isEqualTo(0)
      assertThat(backend.requests.map { it.promptName }).isEqualTo(listOf("navigator", "tree-eval", "visual-eval"))
      assertThat(backend.requests.first().model).isEqualTo(SelectedRoleModel.Codex("gpt-6-luna"))
      assertThat(backend.requests.first().settings).isEqualTo(ModelRequestSettings.Codex("low"))
      assertThat(backend.requests.first().systemText.contains("strict JSON")).isTrue()
      assertThat(device.executedActionFlows.size).isEqualTo(2)
      assertThat(backend.requests.last().images.first().label).isEqualTo("Current screenshot")
      assertThat(backend.requests.last().settings).isEqualTo(ModelRequestSettings.Codex(null))
      assertThat(device.closed).isTrue()
      assertThat(backend.closed).isTrue()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test fun `production backend closes on connection failure model failure and caller cancellation`() {
    for (failure in listOf<Exception?>(null, ModelBackendFailure(ModelBackendFailureKind.PROTOCOL), CallerCancellation(Any()))) {
      val dir = createTempDirectory("verity-backend-fail").toFile()
      val backend = Backend(failure)
      try {
        val file = journey(dir, "complete onboarding wizard")
        val command = RunCommand(preflightChecker = { _, _, _, _ -> preflight(backend) }, sessionFactory = { _, _, _ -> if (failure == null) error("connect failed") else FakeDeviceSession() }, clientFactory = { _, _ -> error("API fallback") })
        val invoke = { Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${dir.path}/output run ${file.path}") }
        if (failure is CancellationException) {
          assertThat(assertFailsWith<CancellationException> { invoke() } === failure).isTrue()
        } else {
          assertThat(invoke().statusCode).isEqualTo(if (failure == null) 3 else 5)
        }
        assertThat(backend.closed).isTrue()
      } finally {
        dir.deleteRecursively()
      }
    }
  }

  @Test fun `production preview acquires navigator lazily and closes across success request and write failure`() {
    for (mode in listOf("success", "model", "write", "cancel", "cleanup")) {
      val dir = createTempDirectory("verity-backend-preview").toFile()
      val cancellation = CallerCancellation(Any())
      val backend = Backend(
        when (mode) {
          "model" -> ModelBackendFailure(ModelBackendFailureKind.PROTOCOL)
          "cancel" -> cancellation
          else -> null
        },
        mode == "cleanup",
      )
      var acquisitions = 0
      try {
        val file = journey(dir, "complete onboarding wizard")
        if (mode == "success") {
          file.appendText(
            """  - "[?visible] Ready"
  - complete checkout
""",
          )
        }
        val output = File(dir, "output")
        if (mode == "write") {
          output.mkdirs()
          File(output, "dry-run").writeText("blocked")
        }
        val command = RunCommand(preflightChecker = { _, _, device, inspector ->
          check(!device && !inspector)
          acquisitions++
          preflight(backend).copy(inspectorModel = null)
        }, sessionFactory = { _, _, _ -> error("Preview session") }, clientFactory = { _, _ -> error("API fallback") })
        val invoke = { Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --inspector-model invalid --inspector-effort invalid --output-path ${output.path} run --dry-run ${file.path}") }
        if (mode == "cancel") {
          assertThat(assertFailsWith<CancellationException> { invoke() } === cancellation).isTrue()
        } else {
          assertThat(invoke().statusCode).isEqualTo(
            when (mode) {
              "success" -> 0
              "model" -> 5
              else -> 3
            },
          )
        }
        assertThat(acquisitions).isEqualTo(1)
        if (mode == "success") assertThat(backend.requests.size).isEqualTo(2)
        assertThat(backend.closed).isTrue()
        if (mode in listOf("model", "cancel")) assertThat(output.walkTopDown().any { it.extension == "md" }).isEqualTo(false)
      } finally {
        dir.deleteRecursively()
      }
    }
  }

  @Test fun `mapped preview masks invalid auth and model without backend or device acquisition`() {
    val dir = createTempDirectory("verity-backend-fast").toFile()
    try {
      val file = journey(dir, "Press back")
      val command = RunCommand(preflightChecker = { _, _, _, _ -> error("Fast preview preflight") }, sessionFactory = { _, _, _ -> error("Fast preview session") }, clientFactory = { _, _ -> error("Fast preview client") })
      val result = Verity().subcommands(command).test("--provider invalid --openai-auth invalid --navigator-model invalid --output-path ${dir.path}/output run --dry-run ${file.path}")
      assertThat(result.statusCode).isEqualTo(0)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test fun `automatic scroll uses selected navigator backend without generated flow`() {
    val dir = createTempDirectory("verity-backend-scroll").toFile()
    val requests = mutableListOf<ModelRequest>()
    var closed = false
    val backend = object : ModelRequestBackend {
      override suspend fun execute(request: ModelRequest): Message.Assistant {
        requests += request
        return modelReply("NONE")
      }
      override suspend fun close() {
        closed = true
      }
    }
    try {
      val file = journey(dir, "Tap Missing").apply { writeText(readText().replace("android-tv", "android")) }
      val command = RunCommand(preflightChecker = { _, _, _, _ -> preflight(backend) }, sessionFactory = { _, _, _ -> FakeDeviceSession(me.chrisbanes.verity.core.model.Platform.ANDROID_MOBILE) })
      val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${dir.path}/output run ${file.path}")
      assertThat(result.statusCode).isEqualTo(0)
      assertThat(requests.size).isEqualTo(5)
      assertThat(requests.all { it.systemText.contains("suggest which direction") && it.settings == ModelRequestSettings.Codex("low") }).isTrue()
      assertThat(closed).isTrue()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test fun `mid suite model rejection preserves completed results stops later journeys and write failure wins`() {
    for (writeFailure in listOf(false, true)) {
      val dir = createTempDirectory("verity-backend-suite").toFile()
      val backend = Backend(ModelBackendFailure(ModelBackendFailureKind.PROTOCOL), failAfter = 2)
      try {
        for (name in listOf("a", "b", "c")) journey(dir, "complete onboarding wizard").renameTo(File(dir, "$name.journey.yaml"))
        val output = File(dir, "output")
        val command = RunCommand(preflightChecker = { _, _, _, _ -> preflight(backend) }, sessionFactory = { _, _, _ -> FakeDeviceSession() }, writeJourneyResult = { run, path, result -> if (writeFailure) error("write failure") else run.writeJourneyResult(path, result) })
        val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${output.path} run ${dir.path}")
        assertThat(result.statusCode).isEqualTo(if (writeFailure) 3 else 5)
        assertThat(backend.requests.size).isEqualTo(2)
        assertThat(backend.closed).isTrue()
        val summary = kotlinx.serialization.json.Json.decodeFromString<me.chrisbanes.verity.core.result.SuiteArtifactSummary>(output.walkTopDown().first { it.name == "summary.json" }.readText())
        assertThat(summary.error?.kind).isEqualTo(if (writeFailure) me.chrisbanes.verity.core.result.ArtifactErrorKind.SETUP_FAILURE else me.chrisbanes.verity.core.result.ArtifactErrorKind.MODEL_FAILURE)
        if (!writeFailure) {
          assertThat(summary.total).isEqualTo(2)
          assertThat(summary.passed).isEqualTo(1)
          assertThat(summary.openaiAuth).isEqualTo("chatgpt")
        }
      } finally {
        dir.deleteRecursively()
      }
    }
  }

  @Test fun `successful normal cleanup failure retains completed journey with safe setup result`() {
    val dir = createTempDirectory("verity-backend-cleanup").toFile()
    val backend = Backend(closeFailure = true)
    try {
      val file = journey(dir, "complete onboarding wizard")
      val output = File(dir, "output")
      val command = RunCommand(preflightChecker = { _, _, _, _ -> preflight(backend) }, sessionFactory = { _, _, _ -> FakeDeviceSession() })
      val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${output.path} run ${file.path}")
      assertThat(result.statusCode).isEqualTo(3)
      val summary = kotlinx.serialization.json.Json.decodeFromString<me.chrisbanes.verity.core.result.SuiteArtifactSummary>(output.walkTopDown().first { it.name == "summary.json" }.readText())
      assertThat(summary.error?.kind).isEqualTo(me.chrisbanes.verity.core.result.ArtifactErrorKind.SETUP_FAILURE)
      assertThat(summary.error?.message).isEqualTo("Model backend cleanup failed")
      assertThat(summary.total).isEqualTo(1)
      assertThat(summary.passed).isEqualTo(1)
      assertThat(summary.error?.message?.contains("sk-close-secret")).isEqualTo(false)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test fun `device close failure retains completed results while backend closes and primary keeps cleanup diagnostic`() {
    val dir = createTempDirectory("verity-backend-device-close").toFile()
    val backend = Backend(closeFailure = true)
    val sessionFailure = IllegalStateException("sk-session-secret")
    try {
      val file = journey(dir, "complete onboarding wizard")
      val output = File(dir, "output")
      val session = object : me.chrisbanes.verity.device.DeviceSession by FakeDeviceSession() {
        override fun close(): Unit = throw sessionFailure
      }
      val command = RunCommand(preflightChecker = { _, _, _, _ -> preflight(backend) }, sessionFactory = { _, _, _ -> session })
      val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${output.path} run ${file.path}")
      assertThat(result.statusCode).isEqualTo(3)
      val summary = kotlinx.serialization.json.Json.decodeFromString<me.chrisbanes.verity.core.result.SuiteArtifactSummary>(output.walkTopDown().first { it.name == "summary.json" }.readText())
      assertThat(summary.error?.message).isEqualTo("Device session cleanup failed")
      assertThat(summary.total).isEqualTo(1)
      assertThat(summary.passed).isEqualTo(1)
      assertThat(backend.closed).isTrue()
      assertThat((sessionFailure.suppressed.single() as ModelBackendFailure).kind).isEqualTo(ModelBackendFailureKind.CLEANUP)
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun preflight(backend: ModelRequestBackend) = CliPreflightResult(PreflightReport(), VerityProvider.OpenAI, null, SelectedRoleModel.Codex("gpt-6-luna"), SelectedRoleModel.Codex("gpt-6-luna"), LLMParams(), LLMParams(), backend, OpenAiAuth.CHATGPT, "low", null)
  private fun journey(dir: File, step: String): File = File(dir, "test.journey.yaml").apply { writeText("name: Backend journey\napp: com.example.app\nplatform: android-tv\nsteps:\n  - $step\n") }
}
