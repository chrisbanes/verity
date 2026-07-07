package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import me.chrisbanes.verity.agent.JourneyResult
import me.chrisbanes.verity.agent.SegmentResult

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

      assertThat(result.statusCode).isEqualTo(1)
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

      assertThat(result.statusCode).isEqualTo(1)
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

      assertThat(result.statusCode).isEqualTo(1)
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

      assertThat(result.statusCode).isEqualTo(1)
      assertThat(seen).containsExactly("fail.journey.yaml", "pass.journey.yaml")
      assertThat(result.output).contains("Total: 2")
      assertThat(result.output).contains("Passed: 1")
      assertThat(result.output).contains("Failed: 1")
      assertThat(result.output).contains("Suite result: FAILED")
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun runCommand(
    runner: suspend (List<ResolvedJourney>) -> SuiteRunResult,
  ): RunCommand = RunCommand(suiteRunner = runner)

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
}
