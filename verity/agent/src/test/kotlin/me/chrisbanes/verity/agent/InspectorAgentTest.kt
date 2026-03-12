package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class InspectorAgentTest {
  @Test
  fun `system prompt describes inspector role`() {
    val prompt = InspectorAgent.SYSTEM_PROMPT
    assertThat(prompt).contains("testing inspector")
    assertThat(prompt).contains("JSON")
  }

  @Test
  fun `tree user message includes hierarchy and assertion`() {
    val message = InspectorAgent.buildTreeMessage("hierarchy text", "Home is visible")
    assertThat(message).contains("hierarchy text")
    assertThat(message).contains("Home is visible")
  }

  @Test
  fun `visual user message includes assertion`() {
    val message = InspectorAgent.buildVisualMessage("Backdrop image loads")
    assertThat(message).contains("Backdrop image loads")
  }

  @Test
  fun `parses valid JSON verdict`() {
    val json = """{"passed": true, "reasoning": "Home text found"}"""
    val verdict = InspectorAgent.parseVerdict(json)
    assertThat(verdict.passed).isTrue()
    assertThat(verdict.reasoning).contains("Home text found")
  }

  @Test
  fun `parses JSON with code fences`() {
    val json = "```json\n{\"passed\": false, \"reasoning\": \"not found\"}\n```"
    val verdict = InspectorAgent.parseVerdict(json)
    assertThat(verdict.passed).isFalse()
  }

  @Test
  fun `parse failure returns failed verdict`() {
    val verdict = InspectorAgent.parseVerdict("garbage response")
    assertThat(verdict.passed).isFalse()
    assertThat(verdict.reasoning).contains("parse error")
  }
}
