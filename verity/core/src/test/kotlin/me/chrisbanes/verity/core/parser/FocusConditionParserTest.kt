package me.chrisbanes.verity.core.parser

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class FocusConditionParserTest {
  @Test
  fun `only complete focus forms extract a nonblank target`() {
    for (condition in listOf("Settings is focused", "Settings has focus", "focus is on Settings", " 'Settings' IS FOCUSED ", """FOCUS IS ON "Settings"""")) {
      assertThat(FocusConditionParser.parse(condition)).isEqualTo("Settings")
    }
    for (condition in listOf("Settings is focused now", "Settings has focus on screen", "focus Settings", "is focused", "focus is on", "focus is on ''", "  has focus ")) {
      assertThat(FocusConditionParser.parse(condition)).isNull()
    }
  }
}
