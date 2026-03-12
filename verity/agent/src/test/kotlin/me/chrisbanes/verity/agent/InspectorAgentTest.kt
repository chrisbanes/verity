package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

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

  @Test
  fun `evaluateTree invokes text executor and parses verdict`() = runTest {
    var capturedSystemPrompt = ""
    var capturedMessage = ""
    val agent = InspectorAgent(
      evaluateText = { systemPrompt, userMessage ->
        capturedSystemPrompt = systemPrompt
        capturedMessage = userMessage
        """{"passed": true, "reasoning": "Tree matched"}"""
      },
    )

    val verdict = agent.evaluateTree("hierarchy text", "Home is visible")

    assertThat(capturedSystemPrompt).contains("testing inspector")
    assertThat(capturedMessage).contains("hierarchy text")
    assertThat(capturedMessage).contains("Home is visible")
    assertThat(verdict.passed).isTrue()
    assertThat(verdict.reasoning).contains("Tree matched")
  }

  @Test
  fun `evaluateVisual invokes vision executor with screenshot path and parses verdict`() = runTest {
    var capturedPath: Path? = null
    val agent = InspectorAgent(
      evaluateVisualContent = { _, userMessage, screenshotPath ->
        capturedPath = screenshotPath
        assertThat(userMessage).contains("Hero image renders")
        """{"passed": false, "reasoning": "Image mismatch"}"""
      },
    )

    val verdict = agent.evaluateVisual(Path.of("/tmp/sample.png"), "Hero image renders")

    assertThat(capturedPath).isEqualTo(Path.of("/tmp/sample.png"))
    assertThat(verdict.passed).isFalse()
    assertThat(verdict.reasoning).contains("Image mismatch")
  }
}
