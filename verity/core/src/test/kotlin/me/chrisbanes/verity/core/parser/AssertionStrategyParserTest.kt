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
  fun `infer strategy defaults generic assertions to tree`() {
    val step = JourneyStepParser.parse("[?] Home", AssertionStrategy.INFER)

    assertThat(step).isEqualTo(JourneyStep.Assert("Home", AssertMode.TREE))
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
  fun `infer strategy keeps the visual mode forced by visually`() {
    val step = JourneyStepParser.parse("Verify visually that the Home row is selected", AssertionStrategy.INFER)

    assertThat(step).isEqualTo(JourneyStep.Assert("the Home row is selected", AssertMode.VISUAL))
  }

  @Test
  fun `explicit visually and pinned tree win under every strategy`() {
    AssertionStrategy.entries.forEach { strategy ->
      assertThat(
        JourneyStepParser.parse("Verify visually that the Home row is selected", strategy),
        strategy.name,
      ).isEqualTo(JourneyStep.Assert("the Home row is selected", AssertMode.VISUAL))
      assertThat(
        JourneyStepParser.parse("[?tree] Home is shown", strategy),
        strategy.name,
      ).isEqualTo(JourneyStep.Assert("Home is shown", AssertMode.TREE))
    }
  }

  @Test
  fun `tree strategy beats appearance cues without visually`() {
    assertThat(JourneyStepParser.parse("Check that the poster color is red", AssertionStrategy.TREE))
      .isEqualTo(JourneyStep.Assert("the poster color is red", AssertMode.TREE))
    assertThat(JourneyStepParser.parse("[?] Button color is red", AssertionStrategy.TREE))
      .isEqualTo(JourneyStep.Assert("Button color is red", AssertMode.TREE))
  }

  @Test
  fun `journey loader with tree strategy keeps visually and pinned modes`() {
    val journey = JourneyLoader.fromYaml(
      """
      name: Strategy
      app: com.example
      platform: android-tv
      steps:
        - "[?] Home"
        - Verify Details screen
        - Check visually that the backdrop is shown
        - "[?focused] Settings"
      """.trimIndent(),
      AssertionStrategy.TREE,
    )

    assertThat(journey.steps).isEqualTo(
      listOf(
        JourneyStep.Assert("Home", AssertMode.TREE),
        JourneyStep.Assert("Details screen", AssertMode.TREE),
        JourneyStep.Assert("the backdrop is shown", AssertMode.VISUAL),
        JourneyStep.Assert("Settings", AssertMode.FOCUSED),
      ),
    )
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
