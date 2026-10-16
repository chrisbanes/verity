package me.chrisbanes.verity.core.parser

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import kotlin.test.assertFailsWith
import me.chrisbanes.verity.core.model.JourneyStep

class LiteralConditionParserTest {
  @Test
  fun `only wholly quoted conditions are literal-only`() {
    for (condition in listOf("\"spotlight-item-\"", "'spotlight-item-'", "  \" spotlight-item- \"  ")) {
      assertThat(LiteralConditionParser.parse(condition)).isEqualTo("spotlight-item-")
    }
    assertThat(LiteralConditionParser.parse("\"it's here\"")).isEqualTo("it's here")
    for (condition in listOf("spotlight-item-", "\"a\" and \"b\"", "\"Continue Watching\" is focused", "visually \"x\"", "\"", "\"x'", "'x\"")) {
      assertThat(LiteralConditionParser.parse(condition)).isNull()
    }
    assertFailsWith<IllegalArgumentException> { LiteralConditionParser.parse("\"\"") }
  }

  @Test
  fun `blank quoted conditions are rejected like blank conditions`() {
    for (condition in listOf("\"\"", "' '")) {
      assertFailsWith<IllegalArgumentException> { WaitStepInferrer.infer("Wait until $condition") }
      assertFailsWith<IllegalArgumentException> { LoopStepInferrer.infer("Press D-pad down until $condition") }
    }
    assertThat(WaitStepInferrer.infer("Wait until \"spotlight-item-\" up to 120 seconds")).isEqualTo(JourneyStep.Wait("\"spotlight-item-\"", 120))
  }
}
