package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.ConditionTier
import me.chrisbanes.verity.device.DeviceSession

class ConditionEvaluatorTest {
  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun `blank conditions and bare visual prefixes fail before capture or inspection`() = runTest {
    val session = StateSession()
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> error("unexpected tree inspection") },
      evaluateVisualContent = { _, _, _, _ -> error("unexpected visual inspection") },
    )
    val evaluator = ConditionEvaluator(session, inspector)
    val waiter = ConditionWaiter(evaluator) { testScheduler.currentTime * 1_000_000 }
    for (condition in listOf("", " ", "visually", " VISUALLY \t ")) {
      assertFailsWith<IllegalArgumentException> { evaluator.evaluate(condition) }
      assertFailsWith<IllegalArgumentException> { waiter.await(condition, 1.seconds) }
    }
    assertThat(session.literalChecks).isEqualTo(emptyList())
    assertThat(session.filters).isEqualTo(emptyList())
    assertThat(session.screenshots).isEqualTo(emptyList())
    assertThat(session.executedActionFlows).isEqualTo(emptyList())
  }

  @Test
  fun `nonvisual checks use complete literal then deterministic focus then content tree`() = runTest {
    withContext(Dispatchers.Default) {
      val session = StateSession()
      var treeChecks = 0
      var message = ""
      val inspector = InspectorAgent(
        evaluateTreeContent = { _, userMessage, _ ->
          treeChecks++
          message = userMessage
          inspectionReply("""{"passed":true,"reasoning":"semantic match"}""")
        },
        evaluateVisualContent = { _, _, _, _ -> error("unexpected visual") },
      )
      val evaluator = ConditionEvaluator(session, inspector)
      val literal = evaluator.evaluate("Settings")
      assertThat(literal.tier).isEqualTo(ConditionTier.LITERAL)
      assertThat(literal.verdict.passed).isEqualTo(true)
      val falseFocus = evaluator.evaluate("Settings is focused")
      assertThat(falseFocus.tier).isEqualTo(ConditionTier.FOCUS)
      assertThat(falseFocus.verdict.passed).isEqualTo(false)
      assertThat(treeChecks).isEqualTo(0)
      session.focused = true
      val trueFocus = evaluator.evaluate("focus is on 'Settings'")
      assertThat(trueFocus.tier).isEqualTo(ConditionTier.FOCUS)
      assertThat(trueFocus.verdict.passed).isEqualTo(true)
      assertThat(treeChecks).isEqualTo(0)
      val tree = evaluator.evaluate("page has a settings menu")
      assertThat(tree.tier).isEqualTo(ConditionTier.TREE)
      assertThat(tree.verdict.reasoning).isEqualTo("semantic match")
      assertThat(treeChecks).isEqualTo(1)
      assertThat(message).contains("Settings")
      assertThat(message).contains("page has a settings menu")
      assertThat(session.filters).isEqualTo(listOf(HierarchyFilter.CONTENT))
      assertThat(session.literalChecks).isEqualTo(listOf("Settings", "Settings is focused", "focus is on 'Settings'", "page has a settings menu"))
    }
  }

  @Test
  fun `visual prefix requires current screenshot and bypasses every deterministic tier`() = runTest {
    withContext(Dispatchers.Default) {
      val session = StateSession().apply { focused = true }
      var conditionMessage = ""
      var evaluatedPath: Path? = null
      val references = InspectionContext("earlier", listOf(Path.of("reference.png")))
      val inspector = InspectorAgent(
        evaluateTreeContent = { _, _, _ -> error("unexpected tree") },
        evaluateVisualContent = { _, message, path, images ->
          conditionMessage = message
          evaluatedPath = path
          assertThat(images).isEqualTo(references.referenceScreenshots)
          assertThat(withContext(Dispatchers.IO) { Files.size(path) > 0 }).isEqualTo(true)
          inspectionReply("""{"passed":false,"reasoning":"visual mismatch"}""")
        },
      )
      val recorder = object : JourneyArtifactRecorder {
        override suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact = error("optional artifact unavailable")
      }
      val result = ConditionEvaluator(session, inspector, recorder).evaluate("ViSuAlLy Settings", references)
      assertThat(result.tier).isEqualTo(ConditionTier.VISUAL)
      assertThat(result.verdict.passed).isEqualTo(false)
      assertThat(result.verdict.reasoning).isEqualTo("visual mismatch")
      assertThat(conditionMessage).contains("assertion: Settings")
      assertThat(conditionMessage.contains("ViSuAlLy")).isEqualTo(false)
      assertThat(session.literalChecks).isEqualTo(emptyList())
      assertThat(session.filters).isEqualTo(emptyList())
      assertThat(session.screenshots).isEqualTo(listOf(evaluatedPath))
      assertThat(withContext(Dispatchers.IO) { Files.exists(evaluatedPath!!) }).isEqualTo(false)
    }
  }

  @Test
  fun `failed or missing screenshot never reaches the inspector`() = runTest {
    withContext(Dispatchers.Default) {
      val inspector = InspectorAgent(
        evaluateTreeContent = { _, _, _ -> error("unexpected tree") },
        evaluateVisualContent = { _, _, _, _ -> error("inspector must not run without evidence") },
      )
      val session = StateSession()
      session.capture = { error("capture failed") }
      assertFailsWith<IllegalStateException> { ConditionEvaluator(session, inspector).evaluate("visually Settings") }
      assertThat(withContext(Dispatchers.IO) { Files.exists(session.screenshots.single()) }).isEqualTo(false)
      session.screenshots.clear()
      session.capture = { }
      assertFailsWith<IllegalStateException> { ConditionEvaluator(session, inspector).evaluate("visually Settings") }
      assertThat(withContext(Dispatchers.IO) { Files.exists(session.screenshots.single()) }).isEqualTo(false)
    }
  }

  @Test
  fun `inspection failure and outer cancellation propagate with temporary capture cleanup`() = runTest {
    withContext(Dispatchers.Default) {
      for (cancelled in listOf(false, true)) {
        val session = StateSession()
        val inspector = InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unexpected tree") },
          evaluateVisualContent = { _, _, _, _ ->
            if (cancelled) kotlinx.coroutines.awaitCancellation() else error("request failed")
          },
        )
        if (cancelled) {
          assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            kotlinx.coroutines.withTimeout(1000) { ConditionEvaluator(session, inspector).evaluate("visually Settings") }
          }
        } else {
          assertFailsWith<ModelFailureException> { ConditionEvaluator(session, inspector).evaluate("visually Settings") }
        }
        assertThat(withContext(Dispatchers.IO) { Files.exists(session.screenshots.single()) }).isEqualTo(false)
      }
    }
  }

  private class StateSession : DeviceSession {
    override val platform = Platform.ANDROID_TV
    var focused = false
    val literalChecks = mutableListOf<String>()
    val filters = mutableListOf<HierarchyFilter>()
    val screenshots = mutableListOf<Path>()
    var capture: suspend (Path) -> Unit = { path -> withContext<Unit>(Dispatchers.IO) { Files.write(path, byteArrayOf(1)) } }
    val executedActionFlows = mutableListOf<ActionFlow>()
    override suspend fun executeActions(flow: ActionFlow): FlowResult {
      executedActionFlows += flow
      return FlowResult(success = true)
    }

    override suspend fun executeFlow(yaml: String) = FlowResult(true)
    override suspend fun pressKey(keyName: String) = Unit
    override suspend fun captureHierarchyTree() = HierarchyNode(attributes = mapOf("text" to "Settings"), states = if (focused) setOf("focused") else emptySet())
    override suspend fun captureHierarchy(filter: HierarchyFilter): String {
      filters += filter
      return super.captureHierarchy(filter)
    }
    override suspend fun containsText(text: String, ignoreCase: Boolean): Boolean {
      literalChecks += text
      return super.containsText(text, ignoreCase)
    }
    override suspend fun captureScreenshot(output: Path) {
      screenshots.add(output)
      capture(output)
    }
    override suspend fun shell(command: String) = ""
    override suspend fun waitForAnimationToEnd() = Unit
    override fun close() = Unit
  }
}
