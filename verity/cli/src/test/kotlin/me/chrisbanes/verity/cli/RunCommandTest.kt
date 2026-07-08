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
import kotlinx.serialization.json.Json
import me.chrisbanes.verity.agent.JourneyResult
import me.chrisbanes.verity.agent.SegmentResult
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.ArtifactError
import me.chrisbanes.verity.core.result.ArtifactErrorKind
import me.chrisbanes.verity.core.result.ArtifactStatus
import me.chrisbanes.verity.core.result.EvidenceArtifact
import me.chrisbanes.verity.core.result.EvidenceType
import me.chrisbanes.verity.core.result.JourneyArtifactIdentity
import me.chrisbanes.verity.core.result.JourneyArtifactResult
import me.chrisbanes.verity.core.result.SegmentExecutionMode
import me.chrisbanes.verity.core.result.SuiteArtifactSummary
import me.chrisbanes.verity.core.result.SuiteJourneyArtifact

class RunCommandTest {
  private val json = Json { ignoreUnknownKeys = true }

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
              result = JourneyResult(
                resolved.journey.name,
                listOf(
                  SegmentResult(
                    index = 0,
                    passed = true,
                    assertionMode = AssertMode.VISIBLE,
                    assertionDescription = "Home is visible",
                    reasoning = "The Home label is visible",
                    executionMode = SegmentExecutionMode.SLOW,
                    actions = listOf("Open navigation", "Select Home"),
                    generatedFlows = listOf("flows/001-single-journey/segment-000-actions.yaml"),
                    evidence = listOf(EvidenceArtifact(EvidenceType.HIERARCHY, "evidence/001-single-journey/segment-000-tree.txt")),
                    error = ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, "diagnostic only"),
                  ),
                ),
              ),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("--output-path ${outputDir.absolutePath} run ${file.absolutePath}")

      val runDir = File(outputDir, "runs/20260708-143512-single-journey")
      val summaryFile = File(runDir, "summary.json")
      val journeyFile = File(runDir, "journeys/001-single-journey.json")
      assertThat(result.statusCode).isEqualTo(0)
      assertThat(summaryFile.exists()).isEqualTo(true)
      assertThat(journeyFile.exists()).isEqualTo(true)

      val summary = readSummary(summaryFile)
      assertThat(summary.timestamp).isEqualTo("2026-07-08T14:35:12Z")
      assertThat(summary.inputPath).isEqualTo(file.path)
      assertThat(summary.status).isEqualTo(ArtifactStatus.PASSED)
      assertThat(summary.total).isEqualTo(1)
      assertThat(summary.passed).isEqualTo(1)
      assertThat(summary.failed).isEqualTo(0)
      assertThat(summary.journeys).containsExactly(
        SuiteJourneyArtifact(
          path = "journeys/001-single-journey.json",
          name = "Single journey",
          status = ArtifactStatus.PASSED,
        ),
      )
      assertThat(summary.error).isEqualTo(null)

      val journey = readJourney(journeyFile)
      assertThat(journey.journey.name).isEqualTo("Single journey")
      assertThat(journey.journey.file).isEqualTo(file.path)
      assertThat(journey.journey.app).isEqualTo("com.example.app")
      assertThat(journey.journey.platform).isEqualTo(Platform.ANDROID_TV)
      assertThat(journey.passed).isEqualTo(true)
      assertThat(journey.failedAt).isEqualTo(null)
      assertThat(journey.segments.size).isEqualTo(1)

      val segment = journey.segments.single()
      assertThat(segment.index).isEqualTo(0)
      assertThat(segment.passed).isEqualTo(true)
      assertThat(segment.executionMode).isEqualTo(SegmentExecutionMode.SLOW)
      assertThat(segment.actions).containsExactly("Open navigation", "Select Home")
      assertThat(segment.assertion?.description).isEqualTo("Home is visible")
      assertThat(segment.assertion?.mode).isEqualTo(AssertMode.VISIBLE)
      assertThat(segment.reasoning).isEqualTo("The Home label is visible")
      assertThat(segment.generatedFlows).containsExactly("flows/001-single-journey/segment-000-actions.yaml")
      assertThat(segment.evidence).containsExactly(
        EvidenceArtifact(EvidenceType.HIERARCHY, "evidence/001-single-journey/segment-000-tree.txt"),
      )
      assertThat(segment.error).isEqualTo(ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, "diagnostic only"))
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
                segments = listOf(
                  SegmentResult(
                    index = 0,
                    passed = false,
                    assertionMode = AssertMode.TREE,
                    assertionDescription = "Settings exists",
                    reasoning = "Settings was missing",
                    executionMode = SegmentExecutionMode.ASSERTION_ONLY,
                    actions = listOf("Open settings"),
                    generatedFlows = listOf("flows/001-single-journey/segment-000-actions.yaml"),
                    evidence = listOf(EvidenceArtifact(EvidenceType.SCREENSHOT, "evidence/001-single-journey/segment-000-visual.png")),
                    error = ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, "Settings was missing"),
                  ),
                ),
              ),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("--output-path ${outputDir.absolutePath} run ${file.absolutePath}")

      val runDir = File(outputDir, "runs/20260708-143512-single-journey")
      val summaryFile = File(runDir, "summary.json")
      val journeyFile = File(runDir, "journeys/001-single-journey.json")
      assertThat(result.statusCode).isEqualTo(4)
      assertThat(summaryFile.exists()).isEqualTo(true)
      assertThat(journeyFile.exists()).isEqualTo(true)

      val summary = readSummary(summaryFile)
      assertThat(summary.status).isEqualTo(ArtifactStatus.FAILED)
      assertThat(summary.total).isEqualTo(1)
      assertThat(summary.passed).isEqualTo(0)
      assertThat(summary.failed).isEqualTo(1)
      assertThat(summary.journeys).containsExactly(
        SuiteJourneyArtifact(
          path = "journeys/001-single-journey.json",
          name = "Single journey",
          status = ArtifactStatus.FAILED,
        ),
      )
      assertThat(summary.error?.kind).isEqualTo(ArtifactErrorKind.JOURNEY_FAILURE)

      val journey = readJourney(journeyFile)
      assertThat(journey.passed).isEqualTo(false)
      assertThat(journey.failedAt).isEqualTo(0)
      assertThat(journey.segments.size).isEqualTo(1)
      val segment = journey.segments.single()
      assertThat(segment.index).isEqualTo(0)
      assertThat(segment.passed).isEqualTo(false)
      assertThat(segment.executionMode).isEqualTo(SegmentExecutionMode.ASSERTION_ONLY)
      assertThat(segment.actions).containsExactly("Open settings")
      assertThat(segment.assertion?.description).isEqualTo("Settings exists")
      assertThat(segment.assertion?.mode).isEqualTo(AssertMode.TREE)
      assertThat(segment.reasoning).isEqualTo("Settings was missing")
      assertThat(segment.generatedFlows).containsExactly("flows/001-single-journey/segment-000-actions.yaml")
      assertThat(segment.evidence).containsExactly(
        EvidenceArtifact(EvidenceType.SCREENSHOT, "evidence/001-single-journey/segment-000-visual.png"),
      )
      assertThat(segment.error).isEqualTo(ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, "Settings was missing"))
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
      val summaryJson = readSummary(summary)
      assertThat(summaryJson.timestamp).isEqualTo("2026-07-08T14:35:12Z")
      assertThat(summaryJson.inputPath).isEqualTo(missing.path)
      assertThat(summaryJson.status).isEqualTo(ArtifactStatus.FAILED)
      assertThat(summaryJson.total).isEqualTo(0)
      assertThat(summaryJson.passed).isEqualTo(0)
      assertThat(summaryJson.failed).isEqualTo(0)
      assertThat(summaryJson.journeys).containsExactly()
      assertThat(summaryJson.error?.kind).isEqualTo(ArtifactErrorKind.PARSER_FAILURE)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `unresolved input failure exits 2 and writes summary`() {
    val dir = createTempDirectory("verity-run-unresolved-artifacts").toFile()
    try {
      val outputDir = File(dir, "output")

      val result = Verity()
        .subcommands(runCommand(clock = fixedClock()) { error("Suite runner should not be called") })
        .test("--output-path ${outputDir.absolutePath} run")

      val summary = File(outputDir, "runs/20260708-143512-run/summary.json")
      assertThat(result.statusCode).isEqualTo(2)
      assertThat(summary.exists()).isEqualTo(true)
      val summaryJson = readSummary(summary)
      assertThat(summaryJson.timestamp).isEqualTo("2026-07-08T14:35:12Z")
      assertThat(summaryJson.inputPath).isEqualTo("")
      assertThat(summaryJson.status).isEqualTo(ArtifactStatus.FAILED)
      assertThat(summaryJson.total).isEqualTo(0)
      assertThat(summaryJson.passed).isEqualTo(0)
      assertThat(summaryJson.failed).isEqualTo(0)
      assertThat(summaryJson.journeys).containsExactly()
      assertThat(summaryJson.error?.kind).isEqualTo(ArtifactErrorKind.PARSER_FAILURE)
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

  private fun readSummary(file: File): SuiteArtifactSummary = json.decodeFromString(SuiteArtifactSummary.serializer(), file.readText())

  private fun readJourney(file: File): JourneyArtifactResult = json.decodeFromString(JourneyArtifactResult.serializer(), file.readText())

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
