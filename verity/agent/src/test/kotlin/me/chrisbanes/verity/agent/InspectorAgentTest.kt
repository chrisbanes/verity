package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
  fun `invalid replies fail instead of returning a negative verdict`() {
    for (reply in listOf(
      "garbage response", "", "  ", "{}", "[]", "true", "null", "{passed:true,reasoning:ok}",
      """{"passed":"true","reasoning":"ok"}""", """{"passed":null,"reasoning":"ok"}""",
      """{"passed":true,"reasoning":5}""", """{"passed":true,"reasoning":null}""",
      """{"passed":true}""", """{"reasoning":"ok"}""",
    )) {
      assertFailsWith<ModelFailureException> { InspectorAgent.parseVerdict(reply) }
    }
  }

  @Test
  fun `evaluateTree invokes text executor and parses verdict`() = runTest {
    var capturedMessage = ""
    val agent = InspectorAgent(
      evaluateTreeContent = { _, userMessage, _ ->
        capturedMessage = userMessage
        inspectionReply("""{"passed": true, "reasoning": "Tree matched"}""")
      },
      evaluateVisualContent = { _, _, _, _ -> error("unused") },
    )

    val verdict = agent.evaluateTree("hierarchy text", "Home is visible")

    assertThat(capturedMessage).contains("hierarchy text")
    assertThat(capturedMessage).contains("Home is visible")
    assertThat(verdict.passed).isTrue()
    assertThat(verdict.reasoning).contains("Tree matched")
  }

  @Test
  fun `evaluateVisual invokes vision executor with screenshot path and parses verdict`() = runTest {
    var capturedPath: Path? = null
    val agent = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> error("unused") },
      evaluateVisualContent = { _, userMessage, screenshotPath, _ ->
        capturedPath = screenshotPath
        assertThat(userMessage).contains("Hero image renders")
        inspectionReply("""{"passed": false, "reasoning": "Image mismatch"}""")
      },
    )

    val verdict = agent.evaluateVisual(Path.of("/tmp/sample.png"), "Hero image renders")

    assertThat(capturedPath).isEqualTo(Path.of("/tmp/sample.png"))
    assertThat(verdict.passed).isFalse()
    assertThat(verdict.reasoning).contains("Image mismatch")
  }

  @Test
  fun `truncation fails before otherwise valid verdict decoding`() = runTest {
    for (reason in listOf("length", "MAX_TOKENS", "Incomplete")) {
      val inspector = InspectorAgent(
        evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed":true,"reasoning":"ok"}""", reason) },
        evaluateVisualContent = { _, _, _, _ -> inspectionReply("""{"passed":true,"reasoning":"ok"}""", reason) },
      )
      assertThat(assertFailsWith<ModelFailureException> { inspector.evaluateTree("tree", "Home") }.failure).isEqualTo(ModelFailureKind.TRUNCATED)
      assertThat(assertFailsWith<ModelFailureException> { inspector.evaluateVisual(Path.of("current.png"), "Home") }.failure).isEqualTo(ModelFailureKind.TRUNCATED)
    }
  }

  @Test
  fun `both inspector requests own a timeout while caller cancellation propagates`() = runTest {
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> kotlinx.coroutines.awaitCancellation() },
      evaluateVisualContent = { _, _, _, _ -> kotlinx.coroutines.awaitCancellation() },
    )
    assertThat(assertFailsWith<ModelFailureException> { inspector.evaluateTree("tree", "Home") }.failure).isEqualTo(ModelFailureKind.TIMEOUT)
    assertThat(assertFailsWith<ModelFailureException> { inspector.evaluateVisual(Path.of("current.png"), "Home") }.failure).isEqualTo(ModelFailureKind.TIMEOUT)
    assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
      kotlinx.coroutines.withTimeout(100) { inspector.evaluateTree("tree", "Home") }
    }
    assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
      kotlinx.coroutines.withTimeout(100) { inspector.evaluateVisual(Path.of("current.png"), "Home") }
    }
    val cancellation = kotlin.coroutines.cancellation.CancellationException("caller")
    val cancelled = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> throw cancellation },
      evaluateVisualContent = { _, _, _, _ -> throw cancellation },
    )
    assertThat(assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { cancelled.evaluateTree("tree", "Home") }.message).isEqualTo("caller")
    assertThat(assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { cancelled.evaluateVisual(Path.of("current.png"), "Home") }.message).isEqualTo("caller")
  }

  @Test
  fun `request failures expose only fixed stage diagnostics without raw causes`() = runTest {
    val raw = "sk-secret Bearer abc.def.ghi HTTP_BODY_SENTINEL HEADER_SENTINEL"
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> error(raw) },
      evaluateVisualContent = { _, _, _, _ -> error(raw) },
    )
    val tree = assertFailsWith<ModelFailureException> { inspector.evaluateTree("tree", "Home") }
    val visual = assertFailsWith<ModelFailureException> { inspector.evaluateVisual(Path.of("current.png"), "Home") }
    assertThat(tree.message).isEqualTo("Inspector tree request failed")
    assertThat(visual.message).isEqualTo("Inspector visual request failed")
    assertThat(tree.cause).isEqualTo(null)
    assertThat(visual.cause).isEqualTo(null)
  }

  @Test
  fun `reference context is labelled separately from current state`() = runTest {
    val context = InspectionContext("earlier screen", listOf(Path.of("earlier.png")))
    var treeMessage = ""
    var visualMessage = ""
    var references = emptyList<Path>()
    var current: Path? = null
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, message, images ->
        treeMessage = message
        references = images
        inspectionReply("""{"passed":true,"reasoning":"","extra":1}""", "unknown")
      },
      evaluateVisualContent = { _, message, path, images ->
        visualMessage = message
        current = path
        references = images
        inspectionReply("""{"passed":false,"reasoning":""}""")
      },
    )
    assertThat(inspector.evaluateTree("current tree", "Home", context).passed).isTrue()
    assertThat(treeMessage).contains("Reference context")
    assertThat(treeMessage).contains("earlier screen")
    assertThat(treeMessage).contains("current tree")
    assertThat(references).isEqualTo(listOf(Path.of("earlier.png")))
    assertThat(inspector.evaluateVisual(Path.of("current.png"), "Home", context).passed).isFalse()
    assertThat(current).isEqualTo(Path.of("current.png"))
    assertThat(visualMessage).contains("Current screenshot")
    assertThat(visualMessage).contains("Reference screenshot 1")
    assertThat(visualMessage).contains("not proof of the current condition")
  }
}
