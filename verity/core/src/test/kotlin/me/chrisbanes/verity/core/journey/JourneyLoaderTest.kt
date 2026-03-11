package me.chrisbanes.verity.core.journey

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform

class JourneyLoaderTest {
  @Test
  fun `loads journey from YAML file`() {
    val input = javaClass.classLoader.getResourceAsStream("journeys/sample.journey.yaml")!!
    val yaml = input.bufferedReader().readText()
    val journey = JourneyLoader.fromYaml(yaml)

    assertThat(journey.name).isEqualTo("Browse feed and open detail")
    assertThat(journey.app).isEqualTo("com.example.social")
    assertThat(journey.platform).isEqualTo(Platform.ANDROID_MOBILE)
    assertThat(journey.steps).hasSize(6)
  }

  @Test
  fun `parses step types correctly`() {
    val input = javaClass.classLoader.getResourceAsStream("journeys/sample.journey.yaml")!!
    val journey = JourneyLoader.fromYaml(input.bufferedReader().readText())

    assertThat(journey.steps[0]).isInstanceOf<JourneyStep.Action>()
    assertThat(journey.steps[1]).isInstanceOf<JourneyStep.Assert>()
    assertThat(journey.steps[2]).isInstanceOf<JourneyStep.Loop>()
    assertThat(journey.steps[3]).isInstanceOf<JourneyStep.Action>()
    assertThat(journey.steps[4]).isInstanceOf<JourneyStep.Assert>()
    assertThat(journey.steps[5]).isInstanceOf<JourneyStep.Assert>()
  }

  @Test
  fun `assert modes are inferred correctly`() {
    val input = javaClass.classLoader.getResourceAsStream("journeys/sample.journey.yaml")!!
    val journey = JourneyLoader.fromYaml(input.bufferedReader().readText())

    // "[?] Home" → short text → VISIBLE
    assertThat((journey.steps[1] as JourneyStep.Assert).mode).isEqualTo(AssertMode.VISIBLE)
    // "[?] Detail page shows title" → long text → TREE
    assertThat((journey.steps[4] as JourneyStep.Assert).mode).isEqualTo(AssertMode.TREE)
    // "[?visual] Backdrop image loads" → pinned VISUAL
    assertThat((journey.steps[5] as JourneyStep.Assert).mode).isEqualTo(AssertMode.VISUAL)
  }

  @Test
  fun `lists journey files from directory`() {
    val dir = File(javaClass.classLoader.getResource("journeys")!!.toURI())
    val files = JourneyLoader.listJourneyFiles(dir)
    assertThat(files).hasSize(1)
    assertThat(files.first().name).isEqualTo("sample.journey.yaml")
  }

  @Test
  fun `lists empty for directory with no journeys`() {
    val tempDir = kotlin.io.path.createTempDirectory("empty-journeys").toFile()
    try {
      val files = JourneyLoader.listJourneyFiles(tempDir)
      assertThat(files).isEmpty()
    } finally {
      tempDir.delete()
    }
  }
}
