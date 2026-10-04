package me.chrisbanes.verity.core.parser

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.charleskorn.kaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlinx.serialization.builtins.ListSerializer
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.journey.JourneySegmenter
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.JourneyStep

class WaitStepInferrerTest {
  @Test
  fun `default explicit casing visual focus and trailing period preserve conditions`() {
    for ((source, expected) in listOf(
      "Wait until Ready" to JourneyStep.Wait("Ready"),
      "Wait until Up to date" to JourneyStep.Wait("Up to date"),
      "Wait until Progress goes up to five" to JourneyStep.Wait("Progress goes up to five"),
      " wAiT  UnTiL  visually Loaded up to 3 seconds. " to JourneyStep.Wait("visually Loaded", 3),
      "Wait until Settings is focused up to 1 second" to JourneyStep.Wait("Settings is focused", 1),
      "Wait until Ready up to 2147483647 seconds" to JourneyStep.Wait("Ready", Int.MAX_VALUE),
    )) {
      assertThat(JourneyStepParser.parse(source)).isEqualTo(expected)
    }
    assertThat(JourneyStepParser.parse("[?tree] Wait until Ready")).isEqualTo(JourneyStep.Assert("Wait until Ready", AssertMode.TREE))
    assertThat(JourneyStepParser.parse("Wait 5 seconds")).isEqualTo(JourneyStep.Action("Wait 5 seconds"))
  }

  @Test
  fun `recognized invalid conditions and limits cannot become actions`() {
    for (source in listOf(
      "Wait until", "Wait until .", "Wait until up to 3 seconds",
      "Wait until Ready up to 0 seconds", "Wait until Ready up to -1 seconds",
      "Wait until Ready up to 2147483648 seconds", "Wait until Ready up to ten seconds",
      "Wait until Ready up to 1.5 seconds", "Wait until Ready up to 3 minutes", "Wait until Ready up to",
    )) {
      assertFailure { JourneyStepParser.parse(source) }
    }
    assertFailure { JourneyStep.Wait(" ") }
    assertFailure { JourneyStep.Wait("Ready", 0) }
  }

  @Test
  fun `waits round trip through YAML and load as separate ordered actionless segments`() {
    val steps = listOf(
      JourneyStep.Action("Press BACK"),
      JourneyStep.Wait("Ready"),
      JourneyStep.Assert("Ready", AssertMode.VISIBLE),
      JourneyStep.Wait("visually Loaded", 3),
      JourneyStep.Action("Press HOME"),
    )
    val serializer = ListSerializer(JourneyStepSerializer)
    val yaml = Yaml.default.encodeToString(serializer, steps)
    assertThat(Yaml.default.decodeFromString(serializer, yaml)).isEqualTo(steps)
    val segments = JourneySegmenter.segment(steps)
    assertThat(segments.map { it.index }).isEqualTo(listOf(0, 1, 2, 3, 4))
    assertThat(segments.map { it.wait }).isEqualTo(listOf(null, steps[1], null, steps[3], null))
    assertThat(segments[1].actions).isEqualTo(emptyList())
    assertThat(segments[3].actions).isEqualTo(emptyList())
    val file = File.createTempFile("verity-wait-", ".journey.yaml")
    try {
      file.writeText("name: Wait fixture\napp: app.fixture\nplatform: android\nsteps:\n  - Wait until Ready\n  - Wait until visually Loaded up to 3 seconds\n")
      assertThat(JourneyLoader.fromFile(file).steps).isEqualTo(listOf(JourneyStep.Wait("Ready"), JourneyStep.Wait("visually Loaded", 3)))
    } finally {
      file.delete()
    }
  }
}
