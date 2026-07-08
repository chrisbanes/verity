package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.exists
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import me.chrisbanes.verity.agent.JourneyResult
import me.chrisbanes.verity.agent.SegmentResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.JourneyArtifactIdentity
import me.chrisbanes.verity.core.result.JourneyArtifactResult

class RunCommandTest {

  @Test
  fun `single-file input runs one journey`() {
    val dir = createTempDirectory("verity-run-single").toFile()
    try {
      val file = writeJourney(dir, "single.journey.yaml", "Single journey")
      val seen = mutableListOf<String>()
      val command = runCommand { journeys ->
        seen += journeys.map { it.file.name }
        SuiteRunResult(
          journeys.map { resolved ->
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(resolved.journey.name, listOf(SegmentResult(index = 0, passed = true))),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("run ${file.absolutePath}")

      assertThat(result.statusCode).isEqualTo(0)
      assertThat(seen).containsExactly("single.journey.yaml")
      assertThat(result.output).contains("Journey: Single journey")
      assertThat(result.output).contains("File: ${file.absolutePath}")
      assertThat(result.output).contains("Suite result: PASSED")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `directory input runs journey files in sorted filename order`() {
    val dir = createTempDirectory("verity-run-suite").toFile()
    try {
      writeJourney(dir, "b.journey.yaml", "Second")
      writeJourney(dir, "a.journey.yaml", "First")
      writeJourney(dir, "ignored.yaml", "Ignored")
      val seen = mutableListOf<String>()
      val command = runCommand { journeys ->
        seen += journeys.map { it.file.name }
        SuiteRunResult(
          journeys.map { resolved ->
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(resolved.journey.name, listOf(SegmentResult(index = 0, passed = true))),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("run ${dir.absolutePath}")

      assertThat(result.statusCode).isEqualTo(0)
      assertThat(seen).containsExactly("a.journey.yaml", "b.journey.yaml")
      assertThat(result.output).contains("Total: 2")
      assertThat(result.output).contains("Passed: 2")
      assertThat(result.output).contains("Failed: 0")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `empty directory returns clear non-zero error`() {
    val dir = createTempDirectory("verity-run-empty").toFile()
    try {
      val result = Verity()
        .subcommands(runCommand { error("Suite runner should not be called") })
        .test("run ${dir.absolutePath}")

      assertThat(result.statusCode).isEqualTo(2)
      assertThat(result.output).contains("No journey files found in: ${dir.absolutePath}")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `directory input rejects mixed journey platforms`() {
    val dir = createTempDirectory("verity-run-mixed-platforms").toFile()
    try {
      writeJourney(dir, "android.journey.yaml", "Android journey", platform = "android-tv")
      writeJourney(dir, "ios.journey.yaml", "iOS journey", platform = "ios")

      val result = Verity()
        .subcommands(runCommand { error("Suite runner should not be called") })
        .test("run ${dir.absolutePath}")

      assertThat(result.statusCode).isEqualTo(2)
      assertThat(result.output).contains("Directory suites must use a single platform")
      assertThat(result.output).contains("android.journey.yaml: ANDROID_TV")
      assertThat(result.output).contains("ios.journey.yaml: IOS")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `failing journey output includes file name journey name and failed segment`() {
    val dir = createTempDirectory("verity-run-failure").toFile()
    try {
      val file = writeJourney(dir, "failure.journey.yaml", "Failure journey")
      val command = runCommand { journeys ->
        SuiteRunResult(
          journeys.map { resolved ->
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(
                journeyName = resolved.journey.name,
                segments = listOf(
                  SegmentResult(index = 0, passed = true),
                  SegmentResult(index = 1, passed = false, reasoning = "Expected text was missing"),
                ),
              ),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("run ${file.absolutePath}")

      assertThat(result.statusCode).isEqualTo(4)
      assertThat(result.output).contains("File: ${file.absolutePath}")
      assertThat(result.output).contains("Journey: Failure journey")
      assertThat(result.output).contains("FAILED: Segment 1 failed")
      assertThat(result.output).contains("Expected text was missing")
      assertThat(result.output).contains("Suite result: FAILED")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `multiple journeys aggregate final pass fail outcome`() {
    val dir = createTempDirectory("verity-run-aggregate").toFile()
    try {
      writeJourney(dir, "pass.journey.yaml", "Passing journey")
      writeJourney(dir, "fail.journey.yaml", "Failing journey")
      val seen = mutableListOf<String>()
      val command = runCommand { journeys ->
        seen += journeys.map { it.file.name }
        SuiteRunResult(
          journeys.map { resolved ->
            val passed = resolved.file.name == "pass.journey.yaml"
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(
                journeyName = resolved.journey.name,
                segments = listOf(SegmentResult(index = 0, passed = passed, reasoning = if (passed) "" else "Failed")),
              ),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("run ${dir.absolutePath}")

      assertThat(result.statusCode).isEqualTo(4)
      assertThat(seen).containsExactly("fail.journey.yaml", "pass.journey.yaml")
      assertThat(result.output).contains("Total: 2")
      assertThat(result.output).contains("Passed: 1")
      assertThat(result.output).contains("Failed: 1")
      assertThat(result.output).contains("Suite result: FAILED")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `dry run uses dry-run runner and does not call normal suite runner`() {
    val dir = createTempDirectory("verity-run-dry").toFile()
    try {
      val output = File(dir, "out")
      val file = writeJourney(dir, "dry.journey.yaml", "Dry journey")
      var dryRunCalled = false
      val command = RunCommand(
        suiteRunner = { error("normal suite runner should not be called") },
        dryRunSuiteRunner = { journeys, outputPath ->
          dryRunCalled = true
          assertThat(journeys.map { it.file.name }).containsExactly("dry.journey.yaml")
          assertThat(outputPath).isEqualTo(output)
          DryRunSuiteReport(
            journeys = listOf(
              DryRunJourneyReport(
                resolvedJourney = journeys.single(),
                launchYaml = "appId: com.example.app\n---\n- launchApp",
                segments = emptyList(),
                artifactFile = File(output, "dry-run/dry.md"),
              ),
            ),
          )
        },
      )

      val result = Verity()
        .subcommands(command)
        .test(listOf("--output-path", output.absolutePath, "run", "--dry-run", file.absolutePath))

      assertThat(result.statusCode).isEqualTo(0)
      assertThat(dryRunCalled).isEqualTo(true)
      assertThat(result.output).contains("# Dry Run: Dry journey")
      assertThat(result.output).contains("Artifact: ${File(output, "dry-run/dry.md").path}")
      assertThat(result.output).doesNotContain("Suite result:")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `successful run writes journey result and suite summary`() {
    val dir = createTempDirectory("verity-run-success-artifacts").toFile()
    try {
      val file = writeJourney(dir, "single.journey.yaml", "Single journey")
      val outputDir = File(dir, "output")
      val command = runCommand(clock = fixedClock()) { journeys ->
        SuiteRunResult(
          journeys.map { resolved ->
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(resolved.journey.name, listOf(SegmentResult(index = 0, passed = true))),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("--output-path ${outputDir.absolutePath} run ${file.absolutePath}")

      val runDir = File(outputDir, "runs/20260708-143512-single-journey")
      assertThat(result.statusCode).isEqualTo(0)
      assertThat(File(runDir, "summary.json").exists()).isEqualTo(true)
      assertThat(File(runDir, "journeys/001-single-journey.json").exists()).isEqualTo(true)
      assertThat(File(runDir, "summary.json").readText()).contains("\"status\": \"passed\"")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `journey failure exits 4 and writes failed summary`() {
    val dir = createTempDirectory("verity-run-failure-artifacts").toFile()
    try {
      val file = writeJourney(dir, "single.journey.yaml", "Single journey")
      val outputDir = File(dir, "output")
      val command = runCommand(clock = fixedClock()) { journeys ->
        SuiteRunResult(
          journeys.map { resolved ->
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(
                journeyName = resolved.journey.name,
                segments = listOf(SegmentResult(index = 0, passed = false, reasoning = "Nope")),
              ),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("--output-path ${outputDir.absolutePath} run ${file.absolutePath}")

      val summary = File(outputDir, "runs/20260708-143512-single-journey/summary.json")
      assertThat(result.statusCode).isEqualTo(4)
      assertThat(summary.exists()).isEqualTo(true)
      assertThat(summary.readText()).contains("\"status\": \"failed\"")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `parser input failure exits 2 and writes summary`() {
    val dir = createTempDirectory("verity-run-parser-artifacts").toFile()
    try {
      val missing = File(dir, "missing.journey.yaml")
      val outputDir = File(dir, "output")

      val result = Verity()
        .subcommands(runCommand(clock = fixedClock()) { error("Suite runner should not be called") })
        .test("--output-path ${outputDir.absolutePath} run ${missing.absolutePath}")

      val summary = File(outputDir, "runs/20260708-143512-missing-journey/summary.json")
      assertThat(result.statusCode).isEqualTo(2)
      assertThat(summary.exists()).isEqualTo(true)
      assertThat(summary.readText()).contains("\"kind\": \"parser_failure\"")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `run artifacts create stable directories and relative references`() = kotlinx.coroutines.test.runTest {
    val dir = createTempDirectory("verity-artifacts").toFile()
    try {
      val writer = RunArtifactWriter(
        outputRoot = dir,
        clock = java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T14:35:12Z"), java.time.ZoneOffset.UTC),
      )
      val run = writer.createRun(suiteSlugSource = "My Suite")
      val journey = run.journey(index = 1, name = "Login")

      val flow = journey.saveGeneratedFlow(segmentIndex = 2, label = "actions", yaml = "appId: com.example\n---\n- tapOn: Settings")
      val tree = journey.saveHierarchy(segmentIndex = 2, hierarchy = "[text=Home]")
      val screenshot = journey.screenshotPath(segmentIndex = 3)

      assertThat(run.directory.toFile().name).isEqualTo("20260708-143512-my-suite")
      assertThat(flow).isEqualTo("flows/001-login/segment-002-actions.yaml")
      assertThat(tree).isEqualTo("evidence/001-login/segment-002-tree.txt")
      assertThat(screenshot.relativePath).isEqualTo("evidence/001-login/segment-003-visual.png")
      assertThat(File(run.directory.toFile(), flow).readText()).contains("tapOn: Settings")
      assertThat(File(run.directory.toFile(), tree).readText()).isEqualTo("[text=Home]")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `dry run invalid journey fails before dry-run runner`() {
    val dir = createTempDirectory("verity-run-dry-invalid").toFile()
    try {
      val file = File(dir, "invalid.journey.yaml")
      file.writeText("name: Invalid\nsteps: not-a-list")

      val result = Verity()
        .subcommands(dryRunCommand { _, _ -> error("dry-run runner should not be called") })
        .test("run --dry-run ${file.absolutePath}")

      assertThat(result.statusCode).isEqualTo(1)
      assertThat(result.output).contains("invalid.journey.yaml")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `directory dry run resolves files in sorted order`() {
    val dir = createTempDirectory("verity-run-dry-directory").toFile()
    try {
      writeJourney(dir, "b.journey.yaml", "Second")
      writeJourney(dir, "a.journey.yaml", "First")
      val seen = mutableListOf<String>()

      val result = Verity()
        .subcommands(
          dryRunCommand { journeys, _ ->
            seen += journeys.map { it.file.name }
            DryRunSuiteReport(
              journeys.map { resolved ->
                DryRunJourneyReport(
                  resolvedJourney = resolved,
                  launchYaml = "appId: ${resolved.journey.app}\n---\n- launchApp",
                  segments = emptyList(),
                )
              },
            )
          },
        )
        .test("run --dry-run ${dir.absolutePath}")

      assertThat(result.statusCode).isEqualTo(0)
      assertThat(seen).containsExactly("a.journey.yaml", "b.journey.yaml")
      assertThat(result.output).contains("# Dry Run: First")
      assertThat(result.output).contains("# Dry Run: Second")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `directory dry run rejects mixed platforms`() {
    val dir = createTempDirectory("verity-run-dry-mixed").toFile()
    try {
      writeJourney(dir, "android.journey.yaml", "Android journey", platform = "android-tv")
      writeJourney(dir, "ios.journey.yaml", "iOS journey", platform = "ios")

      val result = Verity()
        .subcommands(dryRunCommand { _, _ -> error("dry-run runner should not be called") })
        .test("run --dry-run ${dir.absolutePath}")

      assertThat(result.statusCode).isEqualTo(1)
      assertThat(result.output).contains("Directory suites must use a single platform")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `dry run writes artifacts with production dry-run runner`() {
    val dir = createTempDirectory("verity-run-dry-artifact").toFile()
    try {
      val output = File(dir, "out")
      val file = writeJourneyWithSteps(
        dir = dir,
        name = "fast.journey.yaml",
        journeyName = "Fast dry journey",
        steps = listOf("press d-pad down", "[?] Home"),
      )

      val result = Verity()
        .subcommands(RunCommand())
        .test(listOf("--output-path", output.absolutePath, "run", "--dry-run", file.absolutePath))

      val artifact = File(output, "dry-run/fast.md")
      assertThat(result.statusCode).isEqualTo(0)
      assertThat(artifact).exists()
      assertThat(artifact.readText()).contains("# Dry Run: Fast dry journey")
      assertThat(result.output).contains("KeyPress(DPAD_DOWN)")
      assertThat(result.output).contains("Assertion: [VISIBLE] Home")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `fast path dry run does not require valid provider config`() {
    val dir = createTempDirectory("verity-run-dry-invalid-provider").toFile()
    val configFile = File("verity/config.yaml")
    val originalConfig = configFile.takeIf { it.exists() }?.readText()
    try {
      configFile.parentFile.mkdirs()
      configFile.writeText("provider: definitely-not-real")
      val output = File(dir, "out")
      val file = writeJourneyWithSteps(
        dir = dir,
        name = "fast-invalid-provider.journey.yaml",
        journeyName = "Fast invalid provider dry run",
        steps = listOf("press d-pad down", "[?] Home"),
      )

      val result = Verity()
        .subcommands(RunCommand())
        .test(listOf("--output-path", output.absolutePath, "run", "--dry-run", file.absolutePath))

      assertThat(result.statusCode).isEqualTo(0)
      assertThat(result.output).contains("KeyPress(DPAD_DOWN)")
    } finally {
      if (originalConfig == null) {
        configFile.delete()
      } else {
        configFile.writeText(originalConfig)
      }
      dir.deleteRecursively()
    }
  }

  @Test
  fun `slow path dry run validates provider when navigator is needed`() {
    val dir = createTempDirectory("verity-run-dry-slow-invalid-provider").toFile()
    val configFile = File("verity/config.yaml")
    val originalConfig = configFile.takeIf { it.exists() }?.readText()
    try {
      configFile.parentFile.mkdirs()
      configFile.writeText("provider: definitely-not-real")
      val output = File(dir, "out")
      val file = writeJourneyWithSteps(
        dir = dir,
        name = "slow-invalid-provider.journey.yaml",
        journeyName = "Slow invalid provider dry run",
        steps = listOf("open the profile drawer", "[?] Home"),
      )

      val result = Verity()
        .subcommands(RunCommand())
        .test(listOf("--output-path", output.absolutePath, "run", "--dry-run", file.absolutePath))

      assertThat(result.statusCode).isEqualTo(1)
      assertThat(result.output).contains("Unknown provider")
      assertThat(result.output).doesNotContain("KeyPress(DPAD_DOWN)")
    } finally {
      if (originalConfig == null) {
        configFile.delete()
      } else {
        configFile.writeText(originalConfig)
      }
      dir.deleteRecursively()
    }
  }

  @Test
  fun `slow path dry run validates nested llm navigator model when navigator is needed`() {
    val dir = createTempDirectory("verity-run-dry-slow-invalid-nested-model").toFile()
    val configFile = File("verity/config.yaml")
    val originalConfig = configFile.takeIf { it.exists() }?.readText()
    try {
      configFile.parentFile.mkdirs()
      configFile.writeText(
        """
        llm:
          provider: ollama
          navigator-model: definitely-not-real
        """.trimIndent(),
      )
      val output = File(dir, "out")
      val file = writeJourneyWithSteps(
        dir = dir,
        name = "slow-invalid-nested-model.journey.yaml",
        journeyName = "Slow invalid nested model dry run",
        steps = listOf("open the profile drawer", "[?] Home"),
      )

      val result = Verity()
        .subcommands(RunCommand())
        .test(listOf("--output-path", output.absolutePath, "run", "--dry-run", file.absolutePath))

      assertThat(result.statusCode).isEqualTo(1)
      assertThat(result.output).contains("Unknown model 'definitely-not-real'")
    } finally {
      if (originalConfig == null) {
        configFile.delete()
      } else {
        configFile.writeText(originalConfig)
      }
      dir.deleteRecursively()
    }
  }

  @Test
  fun `run artifacts reject journey result paths escaping run directory`() = kotlinx.coroutines.test.runTest {
    val dir = createTempDirectory("verity-artifacts-escape").toFile()
    try {
      val run = RunArtifactWriter(
        outputRoot = dir,
        clock = java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T14:35:12Z"), java.time.ZoneOffset.UTC),
      ).createRun(suiteSlugSource = "My Suite")

      assertFailsWith<IllegalArgumentException> {
        run.writeJourneyResult("../escape.json", artifactResult())
      }

      assertThat(File(run.directory.parent.toFile(), "escape.json").exists()).isFalse()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `run artifacts sanitize generated flow labels`() = kotlinx.coroutines.test.runTest {
    val dir = createTempDirectory("verity-artifacts-label").toFile()
    try {
      val run = RunArtifactWriter(
        outputRoot = dir,
        clock = java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T14:35:12Z"), java.time.ZoneOffset.UTC),
      ).createRun(suiteSlugSource = "My Suite")
      val journey = run.journey(index = 1, name = "Login")

      val flow = journey.saveGeneratedFlow(segmentIndex = 2, label = "../bad label", yaml = "flow")

      assertThat(flow).isEqualTo("flows/001-login/segment-002-bad-label.yaml")
      assertThat(File(run.directory.toFile(), flow).readText()).isEqualTo("flow")
      assertThat(File(run.directory.toFile(), "flows/001-login/segment-002-../bad label.yaml").exists()).isFalse()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `run artifacts create collision safe run directories`() = kotlinx.coroutines.test.runTest {
    val dir = createTempDirectory("verity-artifacts-collision").toFile()
    try {
      val writer = RunArtifactWriter(
        outputRoot = dir,
        clock = java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T14:35:12Z"), java.time.ZoneOffset.UTC),
      )

      val first = writer.createRun(suiteSlugSource = "My Suite")
      val second = writer.createRun(suiteSlugSource = "My Suite")

      assertThat(first.directory.toFile().name).isEqualTo("20260708-143512-my-suite")
      assertThat(second.directory.toFile().name).isEqualTo("20260708-143512-my-suite-2")
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun runCommand(
    clock: Clock = Clock.systemUTC(),
    runner: suspend (List<ResolvedJourney>) -> SuiteRunResult,
  ): RunCommand = RunCommand(suiteRunner = runner, clock = clock)

  private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-07-08T14:35:12Z"), ZoneOffset.UTC)

  private fun dryRunCommand(
    runner: suspend (List<ResolvedJourney>, File) -> DryRunSuiteReport,
  ): RunCommand = RunCommand(dryRunSuiteRunner = runner)

  private fun artifactResult(): JourneyArtifactResult = JourneyArtifactResult(
    journey = JourneyArtifactIdentity(
      name = "Login",
      file = "login.journey.yaml",
      app = "com.example.app",
      platform = Platform.ANDROID_TV,
    ),
    passed = true,
  )

  private fun writeJourney(
    dir: File,
    name: String,
    journeyName: String,
    platform: String = "android-tv",
  ): File {
    val file = File(dir, name)
    file.writeText(
      """
      name: $journeyName
      app: com.example.app
      platform: $platform

      steps:
        - "[?] Home"
      """.trimIndent(),
    )
    return file
  }

  private fun writeJourneyWithSteps(
    dir: File,
    name: String,
    journeyName: String,
    platform: String = "android-tv",
    steps: List<String>,
  ): File {
    val file = File(dir, name)
    file.writeText(
      buildString {
        appendLine("name: $journeyName")
        appendLine("app: com.example.app")
        appendLine("platform: $platform")
        appendLine()
        appendLine("steps:")
        steps.forEach { step -> appendLine("  - \"${step.replace("\\", "\\\\").replace("\"", "\\\"")}\"") }
      },
    )
    return file
  }
}
