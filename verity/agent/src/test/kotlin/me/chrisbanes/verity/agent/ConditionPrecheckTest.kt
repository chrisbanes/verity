package me.chrisbanes.verity.agent

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.ConditionTier
import me.chrisbanes.verity.core.result.PrecheckArtifact
import me.chrisbanes.verity.device.FakeDeviceSession

class ConditionPrecheckTest {
  private class Fake(private val reply: suspend (acceptFail: Boolean) -> PrecheckVerdict?) : ConditionPrecheck {
    val states = mutableListOf<PrecheckState>()
    val conditions = mutableListOf<String>()
    val acceptFail = mutableListOf<Boolean>()

    override suspend fun check(state: PrecheckState, condition: String, acceptFail: Boolean): PrecheckVerdict? {
      states += state
      conditions += condition
      this.acceptFail += acceptFail
      return reply(acceptFail)
    }
  }

  private fun verdict(passed: Boolean) = PrecheckVerdict(passed, if (passed) 0.97 else 0.03, "jev-latest", "api.typesafe.ai")

  private class Inspector(passed: Boolean = true) {
    var calls = 0
    val agent = InspectorAgent(
      evaluateTreeContent = { _, _, _ ->
        calls++
        inspectionReply("""{"passed":$passed,"reasoning":"inspected"}""")
      },
      evaluateVisualContent = { _, _, _, _ ->
        calls++
        inspectionReply("""{"passed":true,"reasoning":"visual"}""")
      },
    )
  }

  /** A tree that lacks the prose condition text, so the literal tier cannot answer. */
  private fun session(platform: Platform = Platform.ANDROID_MOBILE) = FakeDeviceSession(platform, hierarchyProvider = { HierarchyNode(attributes = mapOf("text" to "Home")) })

  private fun evaluator(precheck: ConditionPrecheck?, inspector: Inspector, recorder: JourneyArtifactRecorder = NoOpJourneyArtifactRecorder) = ConditionEvaluator(
    session(),
    inspector.agent,
    recorder,
    temporaryScreenshot = { it(java.nio.file.Path.of("virtual.png")) },
    verifyScreenshot = {},
    precheck = precheck,
  )

  @Test
  fun `a confident pass is decided by the pre-check without the inspector`() = runTest {
    val fake = Fake { verdict(true) }
    val inspector = Inspector()

    val result = evaluator(fake, inspector).evaluate("the page is ready", InspectionContext(referenceText = "R"))

    assertThat(result.tier).isEqualTo(ConditionTier.PRECHECK)
    assertThat(result.verdict.passed).isTrue()
    assertThat(result.verdict.reasoning).isEqualTo("Pre-check probability 0.97 (jev-latest)")
    assertThat(result.precheck).isEqualTo(PrecheckArtifact(0.97, "jev-latest", "api.typesafe.ai"))
    assertThat(result.tree).isNotNull()
    assertThat(inspector.calls).isEqualTo(0)
    assertThat(fake.states.single()).all {
      prop(PrecheckState::platform).isEqualTo(Platform.ANDROID_MOBILE)
      prop(PrecheckState::hierarchy).isNotNull().contains("Home")
      prop(PrecheckState::referenceText).isEqualTo("R")
    }
    assertThat(fake.conditions.single()).isEqualTo("the page is ready")
  }

  @Test
  fun `a confident fail is decided by the pre-check without the inspector`() = runTest {
    val inspector = Inspector()
    val result = evaluator(Fake { verdict(false) }, inspector).evaluate("the page is ready")

    assertThat(result.tier).isEqualTo(ConditionTier.PRECHECK)
    assertThat(result.verdict.passed).isFalse()
    assertThat(inspector.calls).isEqualTo(0)
  }

  @Test
  fun `no confident verdict and pre-check errors fall back to the inspector`() = runTest {
    for (reply in listOf<suspend (Boolean) -> PrecheckVerdict?>({ null }, { error("boom") })) {
      val inspector = Inspector()
      val result = evaluator(Fake(reply), inspector).evaluate("the page is ready")

      assertThat(result.tier).isEqualTo(ConditionTier.TREE)
      assertThat(result.verdict.reasoning).isEqualTo("inspected")
      assertThat(result.precheck).isNull()
      assertThat(inspector.calls).isEqualTo(1)
    }
  }

  @Test
  fun `a pre-check state carries exactly one of a hierarchy and a screenshot`() {
    assertFailsWith<IllegalArgumentException> { PrecheckState(Platform.ANDROID_TV, "tree", screenshot = java.nio.file.Path.of("s.png")) }
    assertFailsWith<IllegalArgumentException> { PrecheckState(Platform.ANDROID_TV, null) }
    assertThat(PrecheckState(Platform.ANDROID_TV, null, screenshot = java.nio.file.Path.of("s.png")).hierarchy).isNull()
  }

  @Test
  fun `cancellation from the pre-check propagates`() = runTest {
    val inspector = Inspector()
    assertFailsWith<CancellationException> { evaluator(Fake { throw CancellationException("stop") }, inspector).evaluate("the page is ready") }
    assertThat(inspector.calls).isEqualTo(0)
  }

  @Test
  fun `literal, focus and visual conditions never reach the pre-check`() = runTest {
    val fake = Fake { verdict(true) }
    val evaluator = evaluator(fake, Inspector())

    assertThat(evaluator.evaluate("Home").tier).isEqualTo(ConditionTier.LITERAL)
    assertThat(evaluator.evaluate("Home is focused").tier).isEqualTo(ConditionTier.FOCUS)
    assertThat(evaluator.evaluate("visually the page is blue").tier).isEqualTo(ConditionTier.VISUAL)

    assertThat(fake.states).isEmpty()
  }

  @Test
  fun `loop hints make the pre-check unable to decide a failure`() = runTest {
    val fake = Fake { acceptFail -> verdict(false).takeIf { acceptFail } }
    val inspector = Inspector(passed = false)
    val evaluator = evaluator(fake, inspector)

    val hinted = evaluator.evaluate("the page is ready", loopHints = true)
    val plain = evaluator.evaluate("the page is ready")

    assertThat(fake.acceptFail).isEqualTo(listOf(false, true))
    assertThat(hinted.tier).isEqualTo(ConditionTier.TREE)
    assertThat(plain.tier).isEqualTo(ConditionTier.PRECHECK)
    assertThat(inspector.calls).isEqualTo(1)
  }

  @Test
  fun `a pre-check verdict saves the same hierarchy evidence as an inspected one`() = runTest {
    val saved = mutableListOf<Pair<Int, String>>()
    val recorder = object : JourneyArtifactRecorder {
      override suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String? {
        saved += segmentIndex to hierarchy
        return "hierarchy.txt"
      }
    }
    val result = evaluator(Fake { verdict(true) }, Inspector(), recorder).evaluate("the page is ready")

    assertThat(result.evidence).isNotEmpty()
    assertThat(saved.size).isEqualTo(1)
  }

  @Test
  fun `a wait accepts a pre-check pass and keeps per-check evidence paths`() = runTest {
    val paths = mutableListOf<Int>()
    val recorder = object : JourneyArtifactRecorder {
      override fun forWaitCheck(checkIndex: Int): JourneyArtifactRecorder = object : JourneyArtifactRecorder {
        override suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String? {
          paths += checkIndex
          return "wait-${checkIndex.toString().padStart(3, '0')}/hierarchy.txt"
        }
      }
    }
    val inspector = Inspector()
    val waiter = ConditionWaiter(evaluator(Fake { verdict(true) }, inspector, recorder)) { testScheduler.currentTime * 1_000_000 }

    val result = waiter.await("the page is ready", 5.seconds)

    assertThat(result.satisfied).isTrue()
    assertThat(result.checks).isEqualTo(1)
    assertThat(result.lastEvaluation?.tier).isEqualTo(ConditionTier.PRECHECK)
    assertThat(result.lastEvaluation?.evidence?.single()?.path).isEqualTo("wait-000/hierarchy.txt")
    assertThat(paths).isEqualTo(listOf(0))
    assertThat(inspector.calls).isEqualTo(0)
  }

  @Test
  fun `a pre-check that hangs past the wait deadline ends the wait without an exception`() = runTest {
    val waiter = ConditionWaiter(evaluator(Fake { awaitCancellation() }, Inspector())) { testScheduler.currentTime * 1_000_000 }

    val result = waiter.await("the page is ready", 2.seconds)

    assertThat(result.satisfied).isFalse()
  }

  private fun orchestrator(precheck: ConditionPrecheck?, inspector: Inspector, platform: Platform = Platform.ANDROID_TV) = Orchestrator(
    session(platform),
    navigatorFactory = { NavigatorAgent("unused") { _, _ -> error("unexpected navigator") } },
    inspectorFactory = { inspector.agent },
    precheck = precheck,
  )

  private fun journey(vararg steps: JourneyStep) = Journey("precheck", "com.example", Platform.ANDROID_TV, steps.toList())

  @Test
  fun `orchestrator pre-checks loop and wait conditions but never assertions`() = runTest {
    val fake = Fake { verdict(true) }
    val inspector = Inspector()

    val result = orchestrator(fake, inspector).run(
      journey(
        JourneyStep.Loop("Press D-pad down; press D-pad right", "the row is ready", 3),
        JourneyStep.Wait("the page is ready", 3),
        JourneyStep.Assert("the page is ready", AssertMode.TREE),
      ),
    )

    val (loop, wait, assertion) = result.segments
    assertThat(loop.loop?.tier).isEqualTo(ConditionTier.PRECHECK)
    assertThat(loop.loop?.precheck).isEqualTo(PrecheckArtifact(0.97, "jev-latest", "api.typesafe.ai"))
    assertThat(wait.wait?.tier).isEqualTo(ConditionTier.PRECHECK)
    assertThat(wait.wait?.precheck).isEqualTo(PrecheckArtifact(0.97, "jev-latest", "api.typesafe.ai"))
    assertThat(assertion.reasoning).isEqualTo("inspected")
    assertThat(fake.conditions).isEqualTo(listOf("the row is ready", "the page is ready"))
    assertThat(inspector.calls).isEqualTo(1)
  }

  @Test
  fun `without a pre-check the same journey is inspected as before`() = runTest {
    val inspector = Inspector()

    val result = orchestrator(null, inspector).run(
      journey(JourneyStep.Loop("Press D-pad down; press D-pad right", "the row is ready", 3), JourneyStep.Wait("the page is ready", 3)),
    )

    val (loop, wait) = result.segments
    assertThat(loop.loop?.tier).isEqualTo(ConditionTier.TREE)
    assertThat(loop.loop?.precheck).isNull()
    assertThat(wait.wait?.precheck).isNull()
    assertThat(inspector.calls).isEqualTo(2)
  }
}
