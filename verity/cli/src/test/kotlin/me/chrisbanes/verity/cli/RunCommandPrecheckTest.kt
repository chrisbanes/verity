package me.chrisbanes.verity.cli

import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.Optional
import javax.imageio.ImageIO
import javax.net.ssl.SSLSession
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.time.Duration
import me.chrisbanes.verity.agent.modelReply
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.core.result.PrecheckSummaryArtifact
import me.chrisbanes.verity.core.result.SuiteArtifactSummary
import me.chrisbanes.verity.core.timing.NoOpPhaseTimer
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession

class RunCommandPrecheckTest {
  /** Answers each inspector request with the next of [passes] (the last repeats), or throws [failure]. */
  private class Backend(private val passes: List<Boolean> = listOf(true), private val failure: Throwable? = null) : ModelRequestBackend {
    val requests = mutableListOf<ModelRequest>()
    override suspend fun execute(request: ModelRequest): Message.Assistant {
      requests += request
      failure?.let { throw it }
      return modelReply("""{"passed":${passes[minOf(requests.size, passes.size) - 1]},"reasoning":"inspected"}""")
    }

    override suspend fun close() = Unit
  }

  private fun preflight(backend: ModelRequestBackend) = CliPreflightResult(PreflightReport(), VerityProvider.OpenAI, null, SelectedRoleModel.Codex("gpt-6-luna"), SelectedRoleModel.Codex("gpt-6-luna"), LLMParams(), LLMParams(), backend, OpenAiAuth.CHATGPT, "low", null)

  private fun journey(dir: File, step: String) = File(dir, "test.journey.yaml").apply {
    writeText("name: Classifier journey\napp: com.example.app\nplatform: android-tv\nsteps:\n  - \"$step\"\n")
  }

  private fun session() = FakeDeviceSession(Platform.ANDROID_TV, hierarchyProvider = { HierarchyNode(attributes = mapOf("text" to "Home")) })

  private fun withClassifier(noul: String, status: Int = 200, block: (port: Int) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      exchange.requestBody.readBytes()
      val body = """{"model":"jev-1.13.0","answers":{"condition":{"type":"noul","noul":$noul}}}""".toByteArray()
      exchange.sendResponseHeaders(status, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
      exchange.close()
    }
    server.start()
    try {
      block(server.address.port)
    } finally {
      server.stop(0)
    }
  }

  /** A session whose screenshots are real 1920x1080 TV-sized PNGs. */
  private class ScreenshotSession(private val delegate: DeviceSession = FakeDeviceSession(Platform.ANDROID_TV)) : DeviceSession by delegate {
    override suspend fun captureScreenshot(output: Path) {
      ImageIO.write(BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB), "png", output.toFile())
    }

    override suspend fun captureScreenshot(output: Path, timeout: Duration) = captureScreenshot(output)
  }

  private fun response(body: String) = object : HttpResponse<String> {
    override fun statusCode() = 200
    override fun request(): HttpRequest = error("unused")
    override fun previousResponse() = Optional.empty<HttpResponse<String>>()
    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
    override fun body() = body
    override fun sslSession() = Optional.empty<SSLSession>()
    override fun uri(): URI = error("unused")
    override fun version() = HttpClient.Version.HTTP_1_1
  }

  /** Runs a visual wait against TypeSafe's base URL with a fake key, answering pre-checks with 0.95 in memory. */
  private fun runVisualWait(precheck: VerityPrecheckConfig, args: String = "", keyEnv: String = "TYPESAFE_API_KEY"): Triple<com.github.ajalt.clikt.testing.CliktCommandTestResult, List<HttpRequest>, List<String>> {
    val dir = createTempDirectory("verity-pc-visual-run").toFile()
    try {
      val sent = java.util.concurrent.CopyOnWriteArrayList<HttpRequest>()
      val backend = Backend()
      val command = RunCommand(
        loadConfig = { VerityConfig(precheck = precheck) },
        preflightChecker = { _, _, _, _ -> preflight(backend) },
        sessionFactory = { _, _, _ -> ScreenshotSession() },
        environment = { if (it == keyEnv) "fake-key" else null },
        precheckFactory = { config, stats, trace, timer, warn ->
          val send: suspend (HttpRequest) -> HttpResponse<String> = { request ->
            sent += request
            response(
              if (config.provider == PrecheckProvider.DECISIONS) {
                """{"model":"gpt-6-luna","answers":[{"type":"predicate","name":"condition","probability":0.99}]}"""
              } else {
                """{"model":"jev-1.13.0","answers":{"condition":{"type":"noul","noul":0.95}}}"""
              },
            )
          }
          if (config.provider == PrecheckProvider.DECISIONS) {
            DecisionsPrecheck(config, stats, trace, timer, warn, send)
          } else {
            SystemOnePrecheck(config, stats, trace, timer, warn, send)
          }
        },
      )
      val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${File(dir, "output").path} run $args ${journey(dir, "Wait until visually the loading spinner is gone").path}")
      return Triple(result, sent.toList(), backend.requests.map { it.promptName })
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `an opted-in visual wait with confirm-pass off is decided by the pre-check without a visual inspection`() {
    val (result, sent, prompts) = runVisualWait(VerityPrecheckConfig(enabled = true, visualEnabled = true, confirmPass = false))

    assertThat(result.statusCode).isEqualTo(0)
    assertThat(result.output).contains("Pre-check: accepted 1, confirmed 0, overruled 0, uncertain fallbacks 0, error fallbacks 0")
    assertThat(prompts).isEmpty()
    assertThat(sent.single().headers().firstValue("TypeSafe-Danger-Zone-Images").orElse(null)).isEqualTo("true")
    assertThat(sent.single().uri().toString()).isEqualTo("https://api.typesafe.ai/v1/systemone")
  }

  @Test
  fun `an opted-in visual wait confirms the pre-check pass with one visual inspection by default`() {
    val (result, sent, prompts) = runVisualWait(VerityPrecheckConfig(enabled = true, visualEnabled = true))

    assertThat(result.statusCode).isEqualTo(0)
    assertThat(result.output).contains("Pre-check: accepted 0, confirmed 1, overruled 0, uncertain fallbacks 0, error fallbacks 0")
    assertThat(prompts).isEqualTo(listOf("visual-eval"))
    assertThat(sent.size).isEqualTo(1)
  }

  @Test
  fun `an opted-in visual wait with the decisions provider reaches the decisions client`() {
    val (result, sent, prompts) = runVisualWait(VerityPrecheckConfig(enabled = true, provider = "decisions", visualEnabled = true, confirmPass = false), keyEnv = "OPENAI_API_KEY")

    assertThat(result.statusCode).isEqualTo(0)
    assertThat(result.output).contains("Pre-check: accepted 1, confirmed 0, overruled 0, uncertain fallbacks 0, error fallbacks 0")
    assertThat(prompts).isEmpty()
    val request = sent.single()
    assertThat(request.uri().toString()).isEqualTo("https://api.openai.com/v1/decisions")
    assertThat(request.headers().firstValue("Authorization").orElse(null)).isEqualTo("Bearer fake-key")
  }

  @Test
  fun `createPrecheck picks the client by provider`() {
    val resolved = ResolvedPrecheck("http://127.0.0.1:1", "m", null, Duration.parse("1s"), 0.9, 0.1)
    fun create(provider: PrecheckProvider) = createPrecheck(resolved.copy(provider = provider), PrecheckStats(), null, NoOpPhaseTimer) {}

    assertThat(create(PrecheckProvider.SYSTEM_ONE)).isInstanceOf<SystemOnePrecheck>()
    assertThat(create(PrecheckProvider.DECISIONS)).isInstanceOf<DecisionsPrecheck>()
  }

  @Test
  fun `an unknown provider exits setup before the preflight, device session or run directory`() {
    val dir = createTempDirectory("verity-pc-provider").toFile()
    try {
      var calls = 0
      val output = File(dir, "output")
      val command = RunCommand(
        loadConfig = { VerityConfig(precheck = VerityPrecheckConfig(enabled = true, provider = "bogus")) },
        preflightChecker = { _, _, _, _ ->
          calls++
          error("preflight must not run")
        },
        sessionFactory = { _, _, _ ->
          calls++
          error("no session")
        },
      )

      val result = Verity().subcommands(command).test("--output-path ${output.path} run ${journey(dir, "Wait until ready").path}")

      assertThat(result.statusCode).isEqualTo(3)
      assertThat(result.output).contains("precheck.provider")
      assertThat(calls).isEqualTo(0)
      assertThat(output.exists()).isEqualTo(false)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `a visual wait is inspected as before without visual-enabled or with the pre-check off`() {
    for ((precheck, args) in listOf(VerityPrecheckConfig(enabled = true) to "", VerityPrecheckConfig(enabled = true, visualEnabled = true) to "--precheck=false")) {
      val (result, sent, prompts) = runVisualWait(precheck, args)

      assertThat(result.statusCode).isEqualTo(0)
      assertThat(sent).isEmpty()
      assertThat(prompts).isEqualTo(listOf("visual-eval"))
    }
  }

  private fun enabledConfig(port: Int, confirmPass: Boolean? = null) = VerityConfig(precheck = VerityPrecheckConfig(enabled = true, baseUrl = "http://127.0.0.1:$port", model = "m", confirmPass = confirmPass))

  private fun summaryOf(output: File) = kotlinx.serialization.json.Json.decodeFromString<SuiteArtifactSummary>(output.walkTopDown().first { it.name == "summary.json" }.readText())

  @Test
  fun `a missing key exits setup before the preflight, device session or model client`() {
    val dir = createTempDirectory("verity-pc-key").toFile()
    try {
      var calls = 0
      val command = RunCommand(
        environment = { null },
        preflightChecker = { _, _, _, _ ->
          calls++
          error("preflight must not run")
        },
        sessionFactory = { _, _, _ ->
          calls++
          error("no session")
        },
        clientFactory = { _, _ ->
          calls++
          error("no client")
        },
      )

      val result = Verity().subcommands(command).test("--output-path ${dir.path}/output run --precheck ${journey(dir, "Wait until ready").path}")

      assertThat(result.statusCode).isEqualTo(3)
      assertThat(result.output).contains("precheck.")
      assertThat(result.output).contains("TYPESAFE_API_KEY")
      assertThat(calls).isEqualTo(0)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `an insecure configured base url exits setup before a device session`() {
    val dir = createTempDirectory("verity-pc-url").toFile()
    try {
      var calls = 0
      val command = RunCommand(
        loadConfig = { VerityConfig(precheck = VerityPrecheckConfig(enabled = true, baseUrl = "http://example.com", model = "m")) },
        preflightChecker = { _, _, _, _ ->
          calls++
          error("preflight must not run")
        },
        sessionFactory = { _, _, _ ->
          calls++
          error("no session")
        },
      )

      val result = Verity().subcommands(command).test("--output-path ${dir.path}/output run ${journey(dir, "Wait until ready").path}")

      assertThat(result.statusCode).isEqualTo(3)
      assertThat(result.output).contains("precheck.base-url")
      assertThat(calls).isEqualTo(0)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `visual-enabled with a non-typesafe base url exits setup before a device session or run directory`() {
    val dir = createTempDirectory("verity-pc-visual").toFile()
    try {
      var calls = 0
      val output = File(dir, "output")
      val command = RunCommand(
        loadConfig = { VerityConfig(precheck = VerityPrecheckConfig(enabled = true, baseUrl = "http://127.0.0.1:1", model = "m", visualEnabled = true)) },
        preflightChecker = { _, _, _, _ ->
          calls++
          error("preflight must not run")
        },
        sessionFactory = { _, _, _ ->
          calls++
          error("no session")
        },
      )

      val result = Verity().subcommands(command).test("--output-path ${output.path} run ${journey(dir, "Wait until visually the loading spinner is gone").path}")

      assertThat(result.statusCode).isEqualTo(3)
      assertThat(result.output).contains("precheck.visual-enabled")
      assertThat(calls).isEqualTo(0)
      assertThat(output.exists()).isEqualTo(false)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `dry run ignores the pre-check entirely, including confirm-pass`() {
    val dir = createTempDirectory("verity-pc-dry").toFile()
    try {
      val file = journey(dir, "Wait until ready").path
      fun dryRun(precheck: VerityPrecheckConfig?, flag: String = "--precheck"): String {
        val command = RunCommand(loadConfig = { VerityConfig(precheck = precheck) }, environment = { error("dry run must not read the environment") })
        val result = Verity().subcommands(command).test("--output-path ${dir.path}/output run --dry-run $flag $file")
        assertThat(result.statusCode).isEqualTo(0)
        return result.output
      }

      val baseline = dryRun(null)
      assertThat(baseline).doesNotContain("Pre-check")
      assertThat(dryRun(VerityPrecheckConfig(confirmPass = false))).isEqualTo(baseline)
      assertThat(dryRun(VerityPrecheckConfig(confirmPass = true))).isEqualTo(baseline)
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun journeyJson(output: File) = output.walkTopDown().first { it.name.endsWith(".json") && it.name != "summary.json" }.readText().filterNot { it.isWhitespace() }

  @Test
  fun `by default a confident pass is confirmed by one inspector request and reported in output, timing, summary and journey`() {
    val dir = createTempDirectory("verity-pc-run").toFile()
    try {
      withClassifier("0.95") { port ->
        val backend = Backend()
        val output = File(dir, "output")
        val command = RunCommand(
          loadConfig = { enabledConfig(port) },
          preflightChecker = { _, _, _, _ -> preflight(backend) },
          sessionFactory = { _, _, _ -> session() },
        )

        val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${output.path} run --timing ${journey(dir, "Wait until the page is ready").path}")

        assertThat(result.statusCode).isEqualTo(0)
        val counts = "Pre-check: accepted 0, confirmed 1, overruled 0, uncertain fallbacks 0, error fallbacks 0"
        assertThat(result.output.split(counts).size - 1).isEqualTo(2)
        assertThat(result.output).contains("Pre-check: m at 127.0.0.1:$port")
        assertThat(result.output).contains("model_request precheck x1")
        assertThat(result.output).contains("model_request tree-eval x1")
        assertThat(result.output.lines().indexOfFirst { it == counts }).isEqualTo(result.output.lines().indexOfFirst { it.startsWith("Failed:") } + 1)
        assertThat(backend.requests.map { it.promptName }).isEqualTo(listOf("tree-eval"))
        assertThat(summaryOf(output).precheck).isEqualTo(PrecheckSummaryArtifact(0, 0, 0, confirmed = 1))
        val json = journeyJson(output)
        assertThat(json).contains("\"tier\":\"tree\"")
        assertThat(json).contains("\"model\":\"jev-1.13.0\"")
        assertThat(json).contains("\"outcome\":\"confirmed\"")
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `with confirm-pass off a confident pass decides the wait, skips the inspector and reports counts in output, timing and summary`() {
    val dir = createTempDirectory("verity-pc-run-off").toFile()
    try {
      withClassifier("0.95") { port ->
        val backend = Backend()
        val output = File(dir, "output")
        val command = RunCommand(
          loadConfig = { enabledConfig(port, confirmPass = false) },
          preflightChecker = { _, _, _, _ -> preflight(backend) },
          sessionFactory = { _, _, _ -> session() },
        )

        val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${output.path} run --timing ${journey(dir, "Wait until the page is ready").path}")

        assertThat(result.statusCode).isEqualTo(0)
        val counts = "Pre-check: accepted 1, confirmed 0, overruled 0, uncertain fallbacks 0, error fallbacks 0"
        assertThat(result.output.split(counts).size - 1).isEqualTo(2)
        assertThat(result.output).contains("model_request precheck x1")
        assertThat(backend.requests).isEmpty()
        assertThat(summaryOf(output).precheck).isEqualTo(PrecheckSummaryArtifact(1, 0, 0))
        val json = journeyJson(output)
        assertThat(json).contains("\"tier\":\"precheck\"")
        assertThat(json).contains("\"model\":\"jev-1.13.0\"")
        assertThat(json).doesNotContain("outcome")
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `an inspector that overrules a pass keeps the wait polling until a pass is confirmed`() {
    val dir = createTempDirectory("verity-pc-overruled").toFile()
    try {
      withClassifier("0.95") { port ->
        val backend = Backend(passes = listOf(false, true))
        val output = File(dir, "output")
        val command = RunCommand(loadConfig = { enabledConfig(port) }, preflightChecker = { _, _, _, _ -> preflight(backend) }, sessionFactory = { _, _, _ -> session() })

        val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${output.path} run ${journey(dir, "Wait until the page is ready").path}")

        assertThat(result.statusCode).isEqualTo(0)
        assertThat(result.output).contains("Pre-check: accepted 0, confirmed 1, overruled 1, uncertain fallbacks 0, error fallbacks 0")
        assertThat(backend.requests.size).isEqualTo(2)
        assertThat(summaryOf(output).precheck).isEqualTo(PrecheckSummaryArtifact(0, 0, 0, confirmed = 1, overruled = 1))
        assertThat(journeyJson(output)).contains("\"outcome\":\"confirmed\"")
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `a model failure while confirming a pass exits with the model failure status`() {
    val dir = createTempDirectory("verity-pc-confirm-failure").toFile()
    try {
      withClassifier("0.95") { port ->
        val backend = Backend(failure = IllegalStateException("backend down"))
        val output = File(dir, "output")
        val command = RunCommand(loadConfig = { enabledConfig(port) }, preflightChecker = { _, _, _, _ -> preflight(backend) }, sessionFactory = { _, _, _ -> session() })

        val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${output.path} run ${journey(dir, "Wait until the page is ready").path}")

        assertThat(result.statusCode).isEqualTo(5)
        assertThat(backend.requests.size).isEqualTo(1)
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `an uncertain answer falls back to the inspector and is counted separately`() {
    val dir = createTempDirectory("verity-pc-uncertain").toFile()
    try {
      withClassifier("0.5") { port ->
        val backend = Backend()
        val output = File(dir, "output")
        val command = RunCommand(loadConfig = { enabledConfig(port) }, preflightChecker = { _, _, _, _ -> preflight(backend) }, sessionFactory = { _, _, _ -> session() })

        val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${output.path} run ${journey(dir, "Wait until the page is ready").path}")

        assertThat(result.statusCode).isEqualTo(0)
        assertThat(result.output).contains("Pre-check: accepted 0, confirmed 0, overruled 0, uncertain fallbacks 1, error fallbacks 0")
        assertThat(backend.requests.map { it.promptName }).isEqualTo(listOf("tree-eval"))
        assertThat(summaryOf(output).precheck).isEqualTo(PrecheckSummaryArtifact(0, 1, 0))
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `a server error falls back to the inspector, warns once on stderr and is counted as an error`() {
    val dir = createTempDirectory("verity-pc-error").toFile()
    try {
      withClassifier("0.95", status = 500) { port ->
        val backend = Backend()
        val output = File(dir, "output")
        val command = RunCommand(loadConfig = { enabledConfig(port) }, preflightChecker = { _, _, _, _ -> preflight(backend) }, sessionFactory = { _, _, _ -> session() })

        val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${output.path} run ${journey(dir, "Wait until the page is ready").path}")

        assertThat(result.statusCode).isEqualTo(0)
        assertThat(result.stderr).contains("Warning: pre-check request to 127.0.0.1:$port failed (HTTP 500)")
        assertThat(result.output).contains("Pre-check: accepted 0, confirmed 0, overruled 0, uncertain fallbacks 0, error fallbacks 1")
        assertThat(backend.requests.map { it.promptName }).isEqualTo(listOf("tree-eval"))
        assertThat(summaryOf(output).precheck).isEqualTo(PrecheckSummaryArtifact(0, 0, 1))
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `an enabled run that fails during setup still reports zero pre-check counts`() {
    val dir = createTempDirectory("verity-pc-setup").toFile()
    try {
      val output = File(dir, "output")
      val command = RunCommand(
        loadConfig = { enabledConfig(1) },
        preflightChecker = { _, _, _, _ -> error("preflight failed") },
        sessionFactory = { _, _, _ -> error("no session") },
      )

      val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${output.path} run ${journey(dir, "Wait until the page is ready").path}")

      assertThat(result.statusCode).isEqualTo(3)
      assertThat(summaryOf(output).precheck).isEqualTo(PrecheckSummaryArtifact(0, 0, 0))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `without configuration nothing about the pre-check appears`() {
    val dir = createTempDirectory("verity-pc-off").toFile()
    try {
      val backend = Backend()
      val output = File(dir, "output")
      val command = RunCommand(loadConfig = { VerityConfig() }, preflightChecker = { _, _, _, _ -> preflight(backend) }, sessionFactory = { _, _, _ -> session() })

      val result = Verity().subcommands(command).test("--provider openai --openai-auth chatgpt --output-path ${output.path} run --timing ${journey(dir, "Wait until the page is ready").path}")

      assertThat(result.statusCode).isEqualTo(0)
      assertThat(result.output).doesNotContain("Pre-check")
      assertThat(result.output).doesNotContain("precheck")
      assertThat(backend.requests.map { it.promptName }).isEqualTo(listOf("tree-eval"))
      assertThat(summaryOf(output).precheck).isNull()
      val written = output.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.joinToString { it.readText() }
      assertThat(written).doesNotContain("precheck")
    } finally {
      dir.deleteRecursively()
    }
  }
}
