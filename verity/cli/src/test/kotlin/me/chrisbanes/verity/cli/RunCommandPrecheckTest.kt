package me.chrisbanes.verity.cli

import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import me.chrisbanes.verity.agent.modelReply
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.core.result.PrecheckSummaryArtifact
import me.chrisbanes.verity.core.result.SuiteArtifactSummary
import me.chrisbanes.verity.device.FakeDeviceSession

class RunCommandPrecheckTest {
  private class Backend : ModelRequestBackend {
    val requests = mutableListOf<ModelRequest>()
    override suspend fun execute(request: ModelRequest): Message.Assistant {
      requests += request
      return modelReply("""{"passed":true,"reasoning":"inspected"}""")
    }

    override suspend fun close() = Unit
  }

  private fun preflight(backend: ModelRequestBackend) = CliPreflightResult(PreflightReport(), VerityProvider.OpenAI, null, SelectedRoleModel.Codex("gpt-6-luna"), SelectedRoleModel.Codex("gpt-6-luna"), LLMParams(), LLMParams(), backend, OpenAiAuth.CHATGPT, "low", null)

  private fun journey(dir: File, step: String) = File(dir, "test.journey.yaml").apply {
    writeText("name: Classifier journey\napp: com.example.app\nplatform: android-tv\nsteps:\n  - \"$step\"\n")
  }

  private fun session() = FakeDeviceSession(Platform.ANDROID_TV, hierarchyProvider = { HierarchyNode(attributes = mapOf("text" to "Home")) })

  private fun withClassifier(noul: String, block: (port: Int) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      exchange.requestBody.readBytes()
      val body = """{"model":"jev-1.13.0","answers":{"condition":{"type":"noul","noul":$noul}}}""".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
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

  private fun enabledConfig(port: Int) = VerityConfig(precheck = VerityPrecheckConfig(enabled = true, baseUrl = "http://127.0.0.1:$port", model = "m"))

  private fun summaryOf(output: File) = kotlinx.serialization.json.Json.decodeFromString<SuiteArtifactSummary>(output.walkTopDown().first { it.name == "summary.json" }.readText())

  @Test
  fun `a missing key exits setup before the preflight, device session or model client`() {
    val dir = createTempDirectory("verity-pc-key").toFile()
    try {
      var calls = 0
      val command = RunCommand(
        environment = { null },
        preflightChecker = { _, _, _, _ -> error("preflight must not run").also { calls++ } },
        sessionFactory = { _, _, _ -> error("no session").also { calls++ } },
        clientFactory = { _, _ -> error("no client").also { calls++ } },
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
        preflightChecker = { _, _, _, _ -> error("preflight must not run").also { calls++ } },
        sessionFactory = { _, _, _ -> error("no session").also { calls++ } },
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
  fun `dry run ignores the pre-check entirely`() {
    val dir = createTempDirectory("verity-pc-dry").toFile()
    try {
      val command = RunCommand(environment = { error("dry run must not read the environment") })

      val result = Verity().subcommands(command).test("--output-path ${dir.path}/output run --dry-run --precheck ${journey(dir, "Wait until ready").path}")

      assertThat(result.statusCode).isEqualTo(0)
      assertThat(result.output).doesNotContain("Pre-check")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `an enabled pre-check decides the wait, skips the inspector and reports counts in output, timing and summary`() {
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
        val counts = "Pre-check: accepted 1, uncertain fallbacks 0, error fallbacks 0"
        assertThat(result.output.split(counts).size - 1).isEqualTo(2)
        assertThat(result.output).contains("Pre-check: m at 127.0.0.1:$port")
        assertThat(result.output).contains("model_request precheck x1")
        assertThat(result.output.lines().indexOfFirst { it == counts }).isEqualTo(result.output.lines().indexOfFirst { it.startsWith("Failed:") } + 1)
        assertThat(backend.requests).isEmpty()
        assertThat(summaryOf(output).precheck).isEqualTo(PrecheckSummaryArtifact(1, 0, 0))
        val journeyJson = output.walkTopDown().first { it.name.endsWith(".json") && it.name != "summary.json" }.readText().filterNot { it.isWhitespace() }
        assertThat(journeyJson).contains("\"tier\":\"precheck\"")
        assertThat(journeyJson).contains("\"model\":\"jev-1.13.0\"")
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
        assertThat(result.output).contains("Pre-check: accepted 0, uncertain fallbacks 1, error fallbacks 0")
        assertThat(backend.requests.map { it.promptName }).isEqualTo(listOf("tree-eval"))
        assertThat(summaryOf(output).precheck).isEqualTo(PrecheckSummaryArtifact(0, 1, 0))
      }
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
