package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import org.junit.jupiter.api.io.TempDir

class RunJourneyResolverTest {
  @TempDir
  lateinit var tempDir: File

  @Test
  fun `cli journey argument wins`() {
    val cliFile = tempDir.resolve("cli.journey.yaml").apply { writeText("name: CLI") }
    val configFile = tempDir.resolve("config.journey.yaml").apply { writeText("name: Config") }

    val resolved = resolveRunJourneyFile(
      cliJourneyPath = cliFile.path,
      configuredJourneysPath = configFile,
    )

    assertThat(resolved).isEqualTo(cliFile)
  }

  @Test
  fun `missing cli journey and unconfigured fallback fails clearly`() {
    val error = assertFailsWith<IllegalArgumentException> {
      resolveRunJourneyFile(
        cliJourneyPath = null,
        configuredJourneysPath = null,
      )
    }

    assertThat(error.message.orEmpty()).contains("Journey path required")
  }

  @Test
  fun `single configured journey file is used when cli argument is omitted`() {
    val configFile = tempDir.resolve("config.journey.yaml").apply { writeText("name: Config") }

    val resolved = resolveRunJourneyFile(
      cliJourneyPath = null,
      configuredJourneysPath = configFile,
    )

    assertThat(resolved).isEqualTo(configFile)
  }

  @Test
  fun `configured directory is used when cli argument is omitted`() {
    val journeysDir = tempDir.resolve("journeys").apply { mkdirs() }
    journeysDir.resolve("only.journey.yaml").writeText("name: Only")

    val resolved = resolveRunJourneyFile(
      cliJourneyPath = null,
      configuredJourneysPath = journeysDir,
    )

    assertThat(resolved).isEqualTo(journeysDir)
  }

  @Test
  fun `multiple journeys in configured directory keep directory as suite input`() {
    val journeysDir = tempDir.resolve("journeys").apply { mkdirs() }
    journeysDir.resolve("one.journey.yaml").writeText("name: One")
    journeysDir.resolve("two.journey.yaml").writeText("name: Two")

    val resolved = resolveRunJourneyFile(
      cliJourneyPath = null,
      configuredJourneysPath = journeysDir,
    )

    assertThat(resolved).isEqualTo(journeysDir)
  }

  @Test
  fun `resolved platform override is applied to executable journey`() {
    val journey = Journey(
      name = "Test",
      app = "com.example",
      platform = Platform.ANDROID_TV,
      steps = listOf(JourneyStep.Action("Open settings")),
    )

    val executableJourney = applyResolvedPlatform(journey, Platform.IOS)

    assertThat(executableJourney.platform).isEqualTo(Platform.IOS)
  }

  @Test
  fun `journey platform is preserved when no platform override exists`() {
    val journey = Journey(
      name = "Test",
      app = "com.example",
      platform = Platform.ANDROID_TV,
      steps = listOf(JourneyStep.Action("Open settings")),
    )

    val executableJourney = applyResolvedPlatform(journey, null)

    assertThat(executableJourney).isEqualTo(journey)
  }
}
