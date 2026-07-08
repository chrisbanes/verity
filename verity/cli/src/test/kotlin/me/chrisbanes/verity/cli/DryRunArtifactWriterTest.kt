package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.Platform

class DryRunArtifactWriterTest {
  @Test
  fun `writes one markdown artifact per journey under dry run directory`() = runTest {
    val output = createTempDirectory("verity-dry-run-output").toFile()
    try {
      val journey =
        Journey(
          name = "Artifact journey",
          app = "com.example.app",
          platform = Platform.ANDROID_TV,
          steps = emptyList(),
        )
      val report =
        DryRunJourneyReport(
          resolvedJourney = ResolvedJourney(File("artifact.journey.yaml"), journey),
          launchYaml = "appId: com.example.app\n---\n- launchApp",
          segments = emptyList(),
        )

      val suite = DryRunArtifactWriter().write(output, DryRunSuiteReport(listOf(report)))

      val written = suite.journeys.single().artifactFile
      assertThat(written?.parentFile?.name).isEqualTo("dry-run")
      assertThat(written?.name).isEqualTo("artifact.md")
      assertThat(written?.readText().orEmpty()).contains("# Dry Run: Artifact journey")
      assertThat(written?.readText().orEmpty()).contains("Artifact: ${written?.path}")
      assertThat(suite.journeys.single().resolvedJourney).isEqualTo(report.resolvedJourney)
    } finally {
      output.deleteRecursively()
    }
  }

  @Test
  fun `writes distinct artifacts for journeys with duplicate basenames`() = runTest {
    val output = createTempDirectory("verity-dry-run-output").toFile()
    try {
      val firstReport = reportFor("First duplicate", File("one/duplicate.journey.yaml"))
      val secondReport = reportFor("Second duplicate", File("two/duplicate.journey.yaml"))

      val suite = DryRunArtifactWriter().write(output, DryRunSuiteReport(listOf(firstReport, secondReport)))

      val firstFile = suite.journeys[0].artifactFile
      val secondFile = suite.journeys[1].artifactFile
      assertThat(firstFile?.name).isEqualTo("duplicate.md")
      assertThat(secondFile?.name).isEqualTo("duplicate-2.md")
      assertThat(firstFile?.path).isNotEqualTo(secondFile?.path)
      assertThat(firstFile?.isFile == true).isTrue()
      assertThat(secondFile?.isFile == true).isTrue()
    } finally {
      output.deleteRecursively()
    }
  }

  @Test
  fun `fails clearly when dry run directory cannot be created`() = runTest {
    val output = createTempDirectory("verity-dry-run-output").toFile()
    try {
      val dryRunPath = File(output, "dry-run")
      dryRunPath.writeText("not a directory")

      val error = assertFailsWith<IllegalStateException> {
        DryRunArtifactWriter().write(
          output,
          DryRunSuiteReport(listOf(reportFor("Blocked artifact", File("blocked.journey.yaml")))),
        )
      }

      assertThat(error.message.orEmpty()).contains(dryRunPath.path)
    } finally {
      output.deleteRecursively()
    }
  }

  private fun reportFor(name: String, file: File): DryRunJourneyReport {
    val journey =
      Journey(
        name = name,
        app = "com.example.app",
        platform = Platform.ANDROID_TV,
        steps = emptyList(),
      )
    return DryRunJourneyReport(
      resolvedJourney = ResolvedJourney(file, journey),
      launchYaml = "appId: com.example.app\n---\n- launchApp",
      segments = emptyList(),
    )
  }
}
