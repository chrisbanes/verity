package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
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
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.ConditionTier
import me.chrisbanes.verity.core.result.EvidenceArtifact
import me.chrisbanes.verity.core.result.EvidenceType
import me.chrisbanes.verity.core.result.PrecheckArtifact
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
    assertThat(session.treeCaptures).isEqualTo(0)
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
      val savedHierarchies = mutableListOf<String>()
      val recorder = object : JourneyArtifactRecorder {
        override suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String? {
          savedHierarchies += hierarchy
          return "hierarchy.txt"
        }
      }
      val evaluator = ConditionEvaluator(session, inspector, recorder)
      val literal = evaluator.evaluate("Settings")
      assertThat(literal.tier).isEqualTo(ConditionTier.LITERAL)
      assertThat(literal.verdict.passed).isEqualTo(true)
      assertThat(session.treeCaptures).isEqualTo(1)
      val falseFocus = evaluator.evaluate("Settings is focused")
      assertThat(falseFocus.tier).isEqualTo(ConditionTier.FOCUS)
      assertThat(falseFocus.verdict.passed).isEqualTo(false)
      assertThat(treeChecks).isEqualTo(0)
      assertThat(session.treeCaptures).isEqualTo(2)
      session.focused = true
      val trueFocus = evaluator.evaluate("focus is on 'Settings'")
      assertThat(trueFocus.tier).isEqualTo(ConditionTier.FOCUS)
      assertThat(trueFocus.verdict.passed).isEqualTo(true)
      assertThat(treeChecks).isEqualTo(0)
      assertThat(session.treeCaptures).isEqualTo(3)
      session.focused = false
      val tree = evaluator.evaluate("page has a settings menu")
      assertThat(tree.tier).isEqualTo(ConditionTier.TREE)
      assertThat(tree.verdict.reasoning).isEqualTo("semantic match")
      assertThat(treeChecks).isEqualTo(1)
      assertThat(session.treeCaptures).isEqualTo(4)
      assertThat(message).contains("Settings")
      assertThat(message).contains("page has a settings menu")
      val expectedHierarchy = HierarchyRenderer.render(HierarchyNode(attributes = mapOf("text" to "Settings")), HierarchyFilter.PROMPT)
      assertThat(savedHierarchies).isEqualTo(listOf(expectedHierarchy))
    }
  }

  @Test
  fun `quoted conditions take only the literal tier and never ask a model`() = runTest {
    val session = StateSession()
    var precheckCalls = 0
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> error("unexpected tree inspection") },
      evaluateVisualContent = { _, _, _, _ -> error("unexpected visual inspection") },
    )
    val evaluator = ConditionEvaluator(session, inspector, precheck = { _, _, _ ->
      precheckCalls++
      null
    })
    for (condition in listOf("\"settings\"", "'Settings'")) {
      val found = evaluator.evaluate(condition)
      assertThat(found.tier).isEqualTo(ConditionTier.LITERAL)
      assertThat(found.verdict.passed).isEqualTo(true)
    }
    for (loopHints in listOf(false, true)) {
      val missing = evaluator.evaluate("\"Account\"", loopHints = loopHints)
      assertThat(missing.tier).isEqualTo(ConditionTier.LITERAL)
      assertThat(missing.verdict.passed).isEqualTo(false)
      assertThat(missing.verdict.reasoning).contains("Account")
    }
    assertThat(precheckCalls).isEqualTo(0)
    assertFailsWith<IllegalArgumentException> { evaluator.evaluate("\" \"") }
    assertThat(session.treeCaptures).isEqualTo(4)
  }

  @Test
  fun `literal-only classification matches the quote boundary and blocks batching`() {
    for (condition in listOf("\"spotlight-item-\"", "'spotlight-item-'")) {
      assertThat(ConditionEvaluator.kind(condition)).isEqualTo(ConditionKind.LITERAL_ONLY)
      assertThat(ConditionEvaluator.canBatchPresses(condition)).isFalse()
    }
    assertThat(ConditionEvaluator.kind("spotlight-item-")).isEqualTo(ConditionKind.PROSE)
    assertThat(ConditionEvaluator.kind("\"a\" and \"b\"")).isEqualTo(ConditionKind.PROSE)
    assertThat(ConditionEvaluator.kind("\"Continue Watching\" is focused")).isEqualTo(ConditionKind.FOCUS)
    assertThat(ConditionEvaluator.kind("visually \"x\"")).isEqualTo(ConditionKind.VISUAL)
    assertThat(ConditionEvaluator.canBatchPresses("spotlight-item-")).isEqualTo(true)
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
      assertThat(session.treeCaptures).isEqualTo(0)
      assertThat(session.screenshots).isEqualTo(listOf(evaluatedPath))
      assertThat(withContext(Dispatchers.IO) { Files.exists(evaluatedPath!!) }).isEqualTo(false)
    }
  }

  @Test
  fun `inspected screenshot hook runs after the verdict while the file exists, for visual checks only`() = runTest {
    withContext(Dispatchers.Default) {
      val order = mutableListOf<String>()
      val inspector = InspectorAgent(
        evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed":true,"reasoning":"tree"}""") },
        evaluateVisualContent = { _, _, _, _ ->
          order += "verdict"
          inspectionReply("""{"passed":true,"reasoning":"visual"}""")
        },
      )
      val hooked = mutableListOf<Path>()
      val evaluator = ConditionEvaluator(
        StateSession(),
        inspector,
        onInspectedScreenshot = { path ->
          order += "hook"
          assertThat(withContext(Dispatchers.IO) { Files.size(path) }).isEqualTo(1L)
          hooked.add(path)
        },
      )
      evaluator.evaluate("page has a settings menu")
      assertThat(hooked).isEqualTo(emptyList())
      evaluator.evaluate("visually Settings")
      assertThat(order).isEqualTo(listOf("verdict", "hook"))
      assertThat(withContext(Dispatchers.IO) { Files.exists(hooked.single()) }).isEqualTo(false)
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

  @Test
  fun `deterministic and tree tiers return the captured tree and only loop checks send hints`() = runTest {
    val messages = mutableListOf<String>()
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, userMessage, _ ->
        messages += userMessage
        inspectionReply("""{"passed":false,"reasoning":"tree"}""")
      },
      evaluateVisualContent = { _, _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"visual"}""") },
    )
    val evaluator = ConditionEvaluator(StateSession(), inspector)
    val captured = HierarchyNode(attributes = mapOf("text" to "Settings"))
    assertThat(evaluator.evaluate("Settings").tree).isEqualTo(captured)
    assertThat(evaluator.evaluate("Settings is focused").tree).isEqualTo(captured)
    assertThat(evaluator.evaluate("page has a menu").tree).isEqualTo(captured)
    assertThat(evaluator.evaluate("page has a menu", loopHints = true).tree).isEqualTo(captured)
    assertThat(evaluator.evaluate("visually Settings", loopHints = true).tree).isNull()
    assertThat(messages.size).isEqualTo(2)
    assertThat(messages[0].contains(InspectorAgent.LOOP_HINTS)).isFalse()
    assertThat(messages[1]).contains(InspectorAgent.LOOP_HINTS)
  }

  @Test
  fun `a supplied settled tree answers every nonvisual tier without capturing`() = runTest {
    val session = object : DeviceSession by StateSession() {
      override suspend fun captureHierarchyTree(): HierarchyNode = error("unexpected capture")
    }
    var visualChecks = 0
    var precheckState: PrecheckState? = null
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> error("unexpected tree inspection") },
      evaluateVisualContent = { _, _, _, _ ->
        visualChecks++
        inspectionReply("""{"passed":true,"reasoning":"visual"}""")
      },
    )
    val precheck = ConditionPrecheck { state, _, _ ->
      precheckState = state
      PrecheckVerdict(passed = true, probability = 0.99, model = "m", host = "h")
    }
    val evaluator = ConditionEvaluator(session, inspector, precheck = precheck)
    val settled = HierarchyNode(attributes = mapOf("text" to "Done"))
    val literal = evaluator.evaluate("Done", tree = settled)
    assertThat(literal.tier).isEqualTo(ConditionTier.LITERAL)
    assertThat(literal.verdict.passed).isEqualTo(true)
    assertThat(literal.tree).isEqualTo(settled)
    val prechecked = evaluator.evaluate("the queue is finished", loopHints = true, tree = settled)
    assertThat(prechecked.tier).isEqualTo(ConditionTier.PRECHECK)
    assertThat(prechecked.tree).isEqualTo(settled)
    assertThat(precheckState?.hierarchy).isEqualTo(HierarchyRenderer.render(settled, HierarchyFilter.PROMPT))
    val visual = evaluator.evaluate("visually Done", tree = settled)
    assertThat(visual.tier).isEqualTo(ConditionTier.VISUAL)
    assertThat(visualChecks).isEqualTo(1)
  }

  @Test
  fun `tree tier sends and saves the prompt rendering without bounds-only wrappers`() = runTest {
    val wrapped = HierarchyNode(
      attributes = mapOf("bounds" to "[0,0][1000,500]"),
      children = listOf(HierarchyNode(attributes = mapOf("text" to "Home", "bounds" to "[10,10][100,50]"))),
    )
    val session = object : DeviceSession by StateSession() {
      override suspend fun captureHierarchyTree() = wrapped
    }
    var message = ""
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, userMessage, _ ->
        message = userMessage
        inspectionReply("""{"passed":true,"reasoning":"ok"}""")
      },
      evaluateVisualContent = { _, _, _, _ -> error("unexpected visual") },
    )
    val saved = mutableListOf<String>()
    val recorder = object : JourneyArtifactRecorder {
      override suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String? {
        saved += hierarchy
        return "hierarchy.txt"
      }
    }

    ConditionEvaluator(session, inspector, recorder).evaluate("home screen is shown")

    val expected = HierarchyRenderer.render(wrapped, HierarchyFilter.PROMPT)
    assertThat(expected).isEqualTo("[text=Home, bounds=[10,10][100,50]]\n")
    assertThat(message).contains(expected)
    assertThat(saved).isEqualTo(listOf(expected))
  }

  /** Records each state and whether its screenshot existed while the check ran. */
  private class VisualFake(private val reply: suspend () -> PrecheckVerdict?) : ConditionPrecheck {
    val states = mutableListOf<PrecheckState>()
    val existed = mutableListOf<Boolean>()
    override suspend fun check(state: PrecheckState, condition: String, acceptFail: Boolean): PrecheckVerdict? {
      states += state
      existed += withContext(Dispatchers.IO) { state.screenshot?.let { Files.exists(it) } == true }
      assertThat(acceptFail).isEqualTo(true)
      return reply()
    }
  }

  private fun visualVerdict(passed: Boolean) = PrecheckVerdict(passed, if (passed) 0.99 else 0.01, "jev-1.13.0", "api.typesafe.ai")

  private class VisualInspector(private val failure: Throwable? = null) {
    val paths = mutableListOf<Path>()
    val agent = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed":true,"reasoning":"tree"}""") },
      evaluateVisualContent = { _, _, path, _ ->
        paths.add(path)
        failure?.let { throw it }
        inspectionReply("""{"passed":true,"reasoning":"visual"}""")
      },
    )
  }

  @Test
  fun `an accepted visual pre-check keeps screenshot evidence and memory without the inspector`() = runTest {
    withContext(Dispatchers.Default) {
      val dir = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-visual-precheck") }
      try {
        val artifact = dir.resolve("visual.png")
        val recorder = object : JourneyArtifactRecorder {
          override suspend fun screenshotPath(segmentIndex: Int) = JourneyScreenshotArtifact(artifact, "evidence/visual.png")
        }
        val session = StateSession()
        val fake = VisualFake { visualVerdict(true) }
        val inspector = VisualInspector()
        val hooked = mutableListOf<Path>()
        val evaluator = ConditionEvaluator(session, inspector.agent, recorder, onInspectedScreenshot = { hooked.add(it) }, visualPrecheck = fake)

        val result = evaluator.evaluate("visually the spinner is gone", InspectionContext(referenceText = "earlier"))

        assertThat(result.tier).isEqualTo(ConditionTier.PRECHECK)
        assertThat(result.verdict.passed).isEqualTo(true)
        assertThat(result.verdict.reasoning).isEqualTo("Pre-check probability 0.99 (jev-1.13.0)")
        assertThat(result.precheck).isEqualTo(PrecheckArtifact(0.99, "jev-1.13.0", "api.typesafe.ai"))
        assertThat(result.evidence).isEqualTo(listOf(EvidenceArtifact(EvidenceType.SCREENSHOT, "evidence/visual.png")))
        assertThat(inspector.paths).isEqualTo(emptyList())
        assertThat(hooked).isEqualTo(listOf(artifact))
        assertThat(session.screenshots).isEqualTo(listOf(artifact))
        assertThat(session.treeCaptures).isEqualTo(0)
        assertThat(fake.states.single()).isEqualTo(PrecheckState(Platform.ANDROID_TV, null, "earlier", screenshot = artifact))
        assertThat(fake.existed).isEqualTo(listOf(true))
      } finally {
        withContext(Dispatchers.IO) { dir.toFile().deleteRecursively() }
      }
    }
  }

  @Test
  fun `a confident visual pre-check fail decides the check and deletes the temporary capture`() = runTest {
    withContext(Dispatchers.Default) {
      val session = StateSession()
      val inspector = VisualInspector()
      val hooked = mutableListOf<Path>()
      val result = ConditionEvaluator(session, inspector.agent, onInspectedScreenshot = { hooked.add(it) }, visualPrecheck = VisualFake { visualVerdict(false) })
        .evaluate("visually the spinner is gone")

      assertThat(result.tier).isEqualTo(ConditionTier.PRECHECK)
      assertThat(result.verdict.passed).isEqualTo(false)
      assertThat(result.evidence).isEqualTo(emptyList())
      assertThat(inspector.paths).isEqualTo(emptyList())
      assertThat(hooked).isEqualTo(session.screenshots)
      assertThat(withContext(Dispatchers.IO) { Files.exists(session.screenshots.single()) }).isEqualTo(false)
    }
  }

  @Test
  fun `an unsure or failing visual pre-check hands the same screenshot to the inspector`() = runTest {
    withContext(Dispatchers.Default) {
      val dir = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-visual-fallback") }
      try {
        for (persisted in listOf(false, true)) {
          for (reply in listOf<suspend () -> PrecheckVerdict?>({ null }, { error("boom") })) {
            val artifact = dir.resolve("visual.png")
            val recorder = object : JourneyArtifactRecorder {
              override suspend fun screenshotPath(segmentIndex: Int) = if (persisted) JourneyScreenshotArtifact(artifact, "evidence/visual.png") else null
            }
            val session = StateSession()
            val fake = VisualFake(reply)
            val inspector = VisualInspector()

            val result = ConditionEvaluator(session, inspector.agent, recorder, visualPrecheck = fake).evaluate("visually the spinner is gone")

            assertThat(result.tier).isEqualTo(ConditionTier.VISUAL)
            assertThat(result.verdict.reasoning).isEqualTo("visual")
            assertThat(result.precheck).isNull()
            assertThat(session.screenshots.size).isEqualTo(1)
            assertThat(fake.states.single().screenshot).isEqualTo(session.screenshots.single())
            assertThat(inspector.paths).isEqualTo(session.screenshots)
            if (!persisted) assertThat(withContext(Dispatchers.IO) { Files.exists(session.screenshots.single()) }).isEqualTo(false)
          }
        }
      } finally {
        withContext(Dispatchers.IO) { dir.toFile().deleteRecursively() }
      }
    }
  }

  @Test
  fun `inspector failure and cancellation during a visual pre-check still delete the temporary capture`() = runTest {
    withContext(Dispatchers.Default) {
      val failing = StateSession()
      assertFailsWith<ModelFailureException> {
        ConditionEvaluator(failing, VisualInspector(IllegalStateException("request failed")).agent, visualPrecheck = VisualFake { null }).evaluate("visually Settings")
      }
      assertThat(withContext(Dispatchers.IO) { Files.exists(failing.screenshots.single()) }).isEqualTo(false)

      val cancelled = StateSession()
      val inspector = VisualInspector()
      val fake = VisualFake { kotlinx.coroutines.awaitCancellation() }
      assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
        kotlinx.coroutines.withTimeout(1000) { ConditionEvaluator(cancelled, inspector.agent, visualPrecheck = fake).evaluate("visually Settings") }
      }
      assertThat(fake.existed).isEqualTo(listOf(true))
      assertThat(inspector.paths).isEqualTo(emptyList())
      assertThat(withContext(Dispatchers.IO) { Files.exists(cancelled.screenshots.single()) }).isEqualTo(false)
    }
  }

  @Test
  fun `wait expiry inside a visual pre-check ends the wait, not the inspector, and deletes the capture`() = runTest {
    withContext(Dispatchers.Default) {
      var now = 0L
      val session = StateSession()
      val inspector = VisualInspector()
      val fake = VisualFake {
        now = 60.seconds.inWholeNanoseconds
        null
      }
      val waiter = ConditionWaiter(ConditionEvaluator(session, inspector.agent, visualPrecheck = fake)) { now }

      val result = waiter.await("visually the spinner is gone", 30.seconds)

      assertThat(result.satisfied).isEqualTo(false)
      assertThat(fake.states.size).isEqualTo(1)
      assertThat(inspector.paths).isEqualTo(emptyList())
      assertThat(withContext(Dispatchers.IO) { Files.exists(session.screenshots.single()) }).isEqualTo(false)
    }
  }

  @Test
  fun `the visual and tree pre-checks each see only their own conditions`() = runTest {
    withContext(Dispatchers.Default) {
      val visual = VisualFake { visualVerdict(true) }
      val tree = VisualFake { error("tree pre-check must not run") }
      val evaluator = ConditionEvaluator(StateSession(), VisualInspector().agent, visualPrecheck = visual)
      assertThat(evaluator.evaluate("the page has a settings menu").tier).isEqualTo(ConditionTier.TREE)
      assertThat(visual.states).isEqualTo(emptyList())

      val treeOnly = ConditionEvaluator(StateSession(), VisualInspector().agent, precheck = tree)
      assertThat(treeOnly.evaluate("visually Settings").tier).isEqualTo(ConditionTier.VISUAL)
      assertThat(tree.states).isEqualTo(emptyList())
    }
  }

  private class StateSession : DeviceSession {
    override val platform = Platform.ANDROID_TV
    var focused = false
    var treeCaptures = 0
    val screenshots = mutableListOf<Path>()
    var capture: suspend (Path) -> Unit = { path -> withContext<Unit>(Dispatchers.IO) { Files.write(path, byteArrayOf(1)) } }
    val executedActionFlows = mutableListOf<ActionFlow>()
    override suspend fun executeActions(flow: ActionFlow): FlowResult {
      executedActionFlows += flow
      return FlowResult(success = true)
    }

    override suspend fun executeFlow(yaml: String) = FlowResult(true)
    override suspend fun pressKey(keyName: String) = Unit
    override suspend fun captureHierarchyTree(): HierarchyNode {
      treeCaptures++
      return HierarchyNode(attributes = mapOf("text" to "Settings"), states = if (focused) setOf("focused") else emptySet())
    }
    override suspend fun captureScreenshot(output: Path) {
      screenshots.add(output)
      capture(output)
    }
    override suspend fun captureScreenshot(output: Path, timeout: kotlin.time.Duration) = captureScreenshot(output)
    override suspend fun waitForAnimationToEnd() = Unit
    override fun close() = Unit
  }
}
