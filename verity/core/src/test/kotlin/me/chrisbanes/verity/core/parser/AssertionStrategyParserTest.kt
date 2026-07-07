package me.chrisbanes.verity.core.parser

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.JourneyStep

class AssertionStrategyParserTest {
  @Test
  fun `infer strategy preserves existing generic assertion inference`() {
    val step = JourneyStepParser.parse("[?] Home", AssertionStrategy.INFER)

    assertThat(step).isEqualTo(JourneyStep.Assert("Home", AssertMode.VISIBLE))
  }

  @Test
  fun `configured strategy applies to generic assertions`() {
    val step = JourneyStepParser.parse("[?] Home", AssertionStrategy.TREE)

    assertThat(step).isEqualTo(JourneyStep.Assert("Home", AssertMode.TREE))
  }

  @Test
  fun `configured strategy applies to natural language assertions`() {
    val step = JourneyStepParser.parse("Verify Home is visible", AssertionStrategy.VISUAL)

    assertThat(step).isEqualTo(JourneyStep.Assert("Home is visible", AssertMode.VISUAL))
  }

  @Test
  fun `pinned assertion mode overrides configured strategy`() {
    val step = JourneyStepParser.parse("[?focused] Home", AssertionStrategy.VISUAL)

    assertThat(step).isEqualTo(JourneyStep.Assert("Home", AssertMode.FOCUSED))
  }

  @Test
  fun `journey loader applies configured strategy before step strings are parsed`() {
    val journey = JourneyLoader.fromYaml(
      """
      name: Strategy
      app: com.example
      platform: android-tv
      steps:
        - "[?] Home"
        - "[?focused] Settings"
        - Verify Details screen
      """.trimIndent(),
      AssertionStrategy.VISUAL,
    )

    assertThat(journey.steps).isEqualTo(
      listOf(
        JourneyStep.Assert("Home", AssertMode.VISUAL),
        JourneyStep.Assert("Settings", AssertMode.FOCUSED),
        JourneyStep.Assert("Details screen", AssertMode.VISUAL),
      ),
    )
  }
}
