package me.chrisbanes.verity.cli

import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.device.FakeDeviceSession

class RunCommandKeyActionTest {
  private val noModel = object : ModelRequestBackend {
    override suspend fun execute(request: ModelRequest): Message.Assistant = error("model must not be called: ${request.promptName}")
    override suspend fun close() = Unit
  }

  private fun block(vararg entries: String) = "```verity-key-actions\n${entries.joinToString("\n")}\n```"

  private fun withProject(context: Map<String, String>, step: String = "Open Quick Actions", body: (journey: File, contextArgs: String, dir: File) -> Unit) {
    val dir = createTempDirectory("verity-key-actions").toFile()
    try {
      val contextDir = File(dir, "context").apply { mkdirs() }
      context.forEach { (name, text) -> File(contextDir, name).writeText(text) }
      val journey = File(dir, "test.journey.yaml").apply {
        writeText("name: Key journey\napp: com.example.app\nplatform: android-tv\nsteps:\n  - $step\n")
      }
      body(journey, "--context-path ${contextDir.path} --output-path ${dir.path}/output", dir)
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun command(session: FakeDeviceSession? = null) = RunCommand(
    preflightChecker = { _, _, _, _ -> if (session == null) error("preflight reached") else preflight() },
    sessionFactory = { _, _, _ -> session ?: error("session reached") },
    clientFactory = { _, _ -> error("API fallback") },
  )

  @Test fun `malformed block exits 3 before preflight in normal and dry run, optional or required`() {
    val context = mapOf("remote.md" to "# Remote\n\n${block("Open Quick Actions: [174]")}")
    for (flags in listOf("", "--require-context")) {
      for (mode in listOf("run", "run --dry-run")) {
        withProject(context) { journey, args, _ ->
          val result = Verity().subcommands(command()).test("$args $flags $mode ${journey.path}")
          assertThat(result.statusCode).isEqualTo(3)
          assertThat(result.output).contains("Invalid key action in remote.md:4")
          assertThat(result.output).doesNotContain("preflight reached")
          assertThat(result.output).doesNotContain("session reached")
        }
      }
    }
  }

  @Test fun `built-in collision uses the journey platform`() {
    withProject(mapOf("remote.md" to block("Press back: 4"))) { journey, args, _ ->
      val result = Verity().subcommands(command()).test("$args run --dry-run ${journey.path}")
      assertThat(result.statusCode).isEqualTo(3)
      assertThat(result.output).contains("matches a built-in ANDROID_TV instruction")
    }
  }

  @Test fun `duplicate across files exits 3 naming both locations`() {
    withProject(mapOf("a.md" to block("Open Quick Actions: 1"), "b.md" to "x\n${block("open quick actions: 2")}")) { journey, args, _ ->
      val result = Verity().subcommands(command()).test("$args run ${journey.path}")
      assertThat(result.statusCode).isEqualTo(3)
      assertThat(result.output).contains("in a.md:2 and b.md:3")
    }
  }

  @Test fun `dry run resolves a key action without a navigator and reports the count`() {
    withProject(mapOf("remote.md" to block("Open Quick Actions: 174"))) { journey, args, dir ->
      val result = Verity().subcommands(command()).test("$args run --dry-run ${journey.path}")
      assertThat(result.statusCode).isEqualTo(0)
      assertThat(result.output).contains("remote.md\nKey actions: 1")
      val report = dir.resolve("output").walkTopDown().filter { it.isFile && it.extension == "md" }.joinToString { it.readText() }
      assertThat(report).contains("KeyCode(keycode=174, longPress=false) from key action in remote.md:2")
    }
  }

  @Test fun `context without blocks prints no key action line`() {
    withProject(mapOf("remote.md" to "# Remote"), step = "Press back") { journey, args, _ ->
      val result = Verity().subcommands(command()).test("$args run --dry-run ${journey.path}")
      assertThat(result.statusCode).isEqualTo(0)
      assertThat(result.output).doesNotContain("Key actions")
    }
  }

  @Test fun `normal run presses the key action on the device without a model call`() {
    withProject(mapOf("remote.md" to block("Open Quick Actions: 174"))) { journey, args, _ ->
      val session = FakeDeviceSession()
      val result = Verity().subcommands(command(session)).test("$args run ${journey.path}")
      assertThat(result.statusCode).isEqualTo(0)
      assertThat(result.output).contains("Key actions: 1")
      assertThat(session.events).contains("key:174:false")
    }
  }

  private fun preflight() = CliPreflightResult(PreflightReport(), VerityProvider.OpenAI, null, SelectedRoleModel.Codex("gpt-6-luna"), SelectedRoleModel.Codex("gpt-6-luna"), LLMParams(), LLMParams(), noModel, OpenAiAuth.CHATGPT, "low", null)
}
