package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
  fun `single configured journey file is used when cli argument is omitted`() {
    val configFile = tempDir.resolve("config.journey.yaml").apply { writeText("name: Config") }

    val resolved = resolveRunJourneyFile(
      cliJourneyPath = null,
      configuredJourneysPath = configFile,
    )

    assertThat(resolved).isEqualTo(configFile)
  }

  @Test
  fun `single journey in configured directory is used when cli argument is omitted`() {
    val journeysDir = tempDir.resolve("journeys").apply { mkdirs() }
    val journeyFile = journeysDir.resolve("only.journey.yaml").apply { writeText("name: Only") }

    val resolved = resolveRunJourneyFile(
      cliJourneyPath = null,
      configuredJourneysPath = journeysDir,
    )

    assertThat(resolved).isEqualTo(journeyFile)
  }

  @Test
  fun `multiple journeys in configured directory fail clearly`() {
    val journeysDir = tempDir.resolve("journeys").apply { mkdirs() }
    journeysDir.resolve("one.journey.yaml").writeText("name: One")
    journeysDir.resolve("two.journey.yaml").writeText("name: Two")

    val error = assertFailsWith<IllegalArgumentException> {
      resolveRunJourneyFile(
        cliJourneyPath = null,
        configuredJourneysPath = journeysDir,
      )
    }

    assertThat(error.message.orEmpty()).contains("Multiple journey files found")
  }
}
