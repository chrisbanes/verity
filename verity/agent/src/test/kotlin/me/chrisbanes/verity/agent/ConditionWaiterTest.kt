package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.IOException
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.containsText
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.ConditionTier
import me.chrisbanes.verity.device.CaptureDeadlineExceededException
import me.chrisbanes.verity.device.CaptureOperation
import me.chrisbanes.verity.device.DeviceSession

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConditionWaiterTest {
  @Test fun `immediate tiers and visual bypass use bounded current capture`() = runTest {
    val session = Session()
    val evaluator = evaluator(session)
    val waiter = ConditionWaiter(evaluator) { testScheduler.currentTime * 1_000_000 }
    val literal = waiter.await("Settings")
    assertThat(literal.lastEvaluation?.tier).isEqualTo(ConditionTier.LITERAL)
    session.tree = { HierarchyNode(mapOf("text" to "Settings"), setOf("focused")) }
    assertThat(waiter.await("Settings is focused").lastEvaluation?.tier).isEqualTo(ConditionTier.FOCUS)
    assertThat(waiter.await("page has settings").lastEvaluation?.tier).isEqualTo(ConditionTier.TREE)
    val treeCount = session.budgets.size
    assertThat(waiter.await("visually Settings").lastEvaluation?.tier).isEqualTo(ConditionTier.VISUAL)
    assertThat(session.budgets.size).isEqualTo(treeCount)
    assertThat(session.screenshotBudgets).isEqualTo(listOf(20.seconds))
    assertThat(session.budgets).isEqualTo(listOf(20.seconds, 20.seconds, 20.seconds))
  }

  @Test fun `serial polls start one second after completed evaluation and share decreasing budget`() = runTest {
    val session = Session()
    val starts = mutableListOf<Long>()
    var count = 0
    session.tree = {
      starts += testScheduler.currentTime
      delay(400)
      HierarchyNode(mapOf("text" to if (++count == 3) "Settings" else "Other"))
    }
    val evaluator = evaluator(session, treePassed = false)
    val result = ConditionWaiter(evaluator) { testScheduler.currentTime * 1_000_000 }.await("Settings", 5.seconds)
    assertThat(result.satisfied).isEqualTo(true)
    assertThat(result.checks).isEqualTo(3)
    assertThat(result.elapsedMs).isEqualTo(3200)
    assertThat(starts).isEqualTo(listOf(0L, 1400L, 2800L))
    assertThat(session.budgets).isEqualTo(listOf(5.seconds, 3600.milliseconds, 2200.milliseconds))
    assertThat(session.peak).isEqualTo(1)
  }

  @Test fun `negative focus never asks model and expiry during delay retains last completed result`() = runTest {
    val session = Session()
    val evaluator = evaluator(session, rejectInspector = true)
    val result = ConditionWaiter(evaluator) { testScheduler.currentTime * 1_000_000 }.await("Settings is focused", 3.seconds)
    assertThat(result.satisfied).isEqualTo(false)
    assertThat(result.checks).isEqualTo(3)
    assertThat(result.lastEvaluation?.tier).isEqualTo(ConditionTier.FOCUS)
    assertThat(session.budgets).isEqualTo(listOf(3.seconds, 2.seconds, 1.seconds))
  }

  @Test fun `capture is joined before timeout and late success is never admitted`() = runTest {
    val session = Session()
    var joined = false
    session.tree = {
      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable) {
          delay(2000)
          joined = true
        }
      }
    }
    val result = ConditionWaiter(evaluator(session)) { testScheduler.currentTime * 1_000_000 }.await("Settings", 3.seconds)
    assertThat(result.satisfied).isEqualTo(false)
    assertThat(result.checks).isEqualTo(0)
    assertThat(result.elapsedMs).isEqualTo(5000)
    assertThat(joined).isEqualTo(true)
    assertThat(session.active).isEqualTo(0)
    assertThat(session.budgets.size).isEqualTo(1)
  }

  @Test fun `exact deadline and synchronous traversal overruns do not admit true`() = runTest {
    var now = 0L
    val session = Session()
    session.tree = {
      now = 3_000_000_000
      HierarchyNode(mapOf("text" to "Settings"))
    }
    val result = ConditionWaiter(evaluator(session)) { now }.await("Settings", 3.seconds)
    assertThat(result.satisfied).isEqualTo(false)
    assertThat(result.checks).isEqualTo(0)
  }

  @Test fun `early typed expiry ordinary capture errors and foreign cancellations remain failures`() = runTest {
    val session = Session()
    val waiter = ConditionWaiter(evaluator(session)) { testScheduler.currentTime * 1_000_000 }
    session.tree = { throw CaptureDeadlineExceededException(CaptureOperation.HIERARCHY) }
    assertFailsWith<CaptureDeadlineExceededException> { waiter.await("Settings", 3.seconds) }
    session.tree = { throw IOException("capture") }
    assertThat(assertFailsWith<IOException> { waiter.await("Settings") }.message).isEqualTo("capture")
    session.tree = { throw CancellationException("foreign") }
    assertThat(assertFailsWith<CancellationException> { waiter.await("Settings") }.message).isEqualTo("foreign")
    session.tree = { withTimeout(100) { awaitCancellation() } }
    assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { waiter.await("Settings") }
  }

  @Test fun `typed capture expiry at shared deadline is timeout`() = runTest {
    var now = 0L
    val session = Session()
    session.tree = {
      now = 3_000_000_000
      throw CaptureDeadlineExceededException(CaptureOperation.HIERARCHY)
    }
    assertThat(ConditionWaiter(evaluator(session)) { now }.await("Settings", 3.seconds).satisfied).isEqualTo(false)
  }

  @Test fun `shorter wait owns model cancellation while request timer owns longer wait even through slow join`() = runTest {
    for (limit in listOf(3.seconds, 40.seconds, 45.seconds)) {
      val session = Session()
      var joined = false
      val inspector = InspectorAgent(
        { _, _, _ ->
          try {
            awaitCancellation()
          } finally {
            withContext(NonCancellable) {
              delay(if (limit == 40.seconds) 1.seconds else 31.seconds)
              joined = true
            }
          }
        },
        { _, _, _, _ -> error("visual") },
      )
      val waiter = ConditionWaiter(ConditionEvaluator(session, inspector)) { testScheduler.currentTime * 1_000_000 }
      if (limit == 3.seconds) {
        assertThat(waiter.await("semantic condition", limit).satisfied).isEqualTo(false)
      } else {
        val failure = assertFailsWith<ModelFailureException> { waiter.await("semantic condition", limit) }
        assertThat(failure.failure).isEqualTo(ModelFailureKind.TIMEOUT)
      }
      assertThat(joined).isEqualTo(true)
    }
  }

  @Test fun `predeadline model failure survives screenshot cleanup and parent cancellation wins`() = runTest {
    for (parentCancels in listOf(false, true)) {
      val session = Session()
      var deleted = false
      val inspector = InspectorAgent({ _, _, _ -> error("tree") }, { _, _, _, _ ->
        delay(1000)
        error("secret provider error")
      })
      val evaluator = ConditionEvaluator(
        session,
        inspector,
        temporaryScreenshot = { inspect ->
          try {
            inspect(Path.of("owned.png"))
          } finally {
            withContext(NonCancellable) {
              delay(5000)
              deleted = true
            }
          }
        },
        verifyScreenshot = {},
      )
      val waiter = ConditionWaiter(evaluator) { testScheduler.currentTime * 1_000_000 }
      if (parentCancels) {
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { withTimeout(2.seconds) { waiter.await("visually ready", 3.seconds) } }
      } else {
        assertThat(assertFailsWith<ModelFailureException> { waiter.await("visually ready", 3.seconds) }.failure).isEqualTo(ModelFailureKind.REQUEST)
      }
      assertThat(deleted).isEqualTo(true)
    }
  }

  @Test fun `hierarchy persistence and screenshot acquisition spend same budget`() = runTest {
    val session = Session()
    val recorder = object : JourneyArtifactRecorder {
      override suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String? {
        delay(3000)
        return "tree.txt"
      }
    }
    val inspector = InspectorAgent({ _, _, _ -> error("late inspector") }, { _, _, _, _ -> error("late visual") })
    val waiter = ConditionWaiter(ConditionEvaluator(session, inspector, recorder)) { testScheduler.currentTime * 1_000_000 }
    assertThat(waiter.await("semantic condition", 3.seconds).checks).isEqualTo(0)
    session.screenshot = { delay(3000) }
    val visual = ConditionWaiter(evaluator(session)) { testScheduler.currentTime * 1_000_000 }.await("visually ready", 3.seconds)
    assertThat(visual.checks).isEqualTo(0)
    assertThat(session.screenshotBudgets).isEqualTo(listOf(3.seconds))
  }

  @Test fun `model time and serial delay exhaust same budget without a later poll`() = runTest {
    val session = Session()
    val inspector = InspectorAgent(
      { _, _, _ ->
        delay(1500)
        inspectionReply("""{"passed":false,"reasoning":"not yet"}""")
      },
      { _, _, _, _ -> error("visual") },
    )
    val result = ConditionWaiter(ConditionEvaluator(session, inspector)) { testScheduler.currentTime * 1_000_000 }.await("semantic", 3.seconds)
    assertThat(result.satisfied).isEqualTo(false)
    assertThat(result.checks).isEqualTo(1)
    assertThat(result.lastEvaluation?.verdict?.reasoning).isEqualTo("not yet")
    assertThat(session.budgets).isEqualTo(listOf(3.seconds, 500.milliseconds))
  }

  @Test fun `screenshot budget decreases after optional artifact path work`() = runTest {
    val session = Session()
    val recorder = object : JourneyArtifactRecorder {
      override suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact? {
        delay(750)
        return null
      }
    }
    val inspector = InspectorAgent({ _, _, _ -> error("tree") }, { _, _, _, _ -> inspectionReply("""{"passed":true,"reasoning":"ready"}""") })
    val evaluator = ConditionEvaluator(
      session,
      inspector,
      recorder,
      temporaryScreenshot = { it(Path.of("virtual.png")) },
      verifyScreenshot = {},
    )
    val result = ConditionWaiter(evaluator) { testScheduler.currentTime * 1_000_000 }.await("visually ready", 3.seconds)
    assertThat(result.satisfied).isEqualTo(true)
    assertThat(session.screenshotBudgets).isEqualTo(listOf(2250.milliseconds))
    assertThat(result.elapsedMs).isEqualTo(750)
  }

  @Test fun `foreign model cancellation and nested timeout propagate under wait ownership`() = runTest {
    for (nested in listOf(false, true)) {
      val session = Session()
      val inspector = InspectorAgent(
        { _, _, _ -> if (nested) withTimeout(100) { awaitCancellation() } else throw CancellationException("foreign model") },
        { _, _, _, _ -> error("visual") },
      )
      val waiter = ConditionWaiter(ConditionEvaluator(session, inspector)) { testScheduler.currentTime * 1_000_000 }
      if (nested) {
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { waiter.await("semantic") }
      } else {
        assertThat(assertFailsWith<CancellationException> { waiter.await("semantic") }.message).isEqualTo("foreign model")
      }
    }
  }

  @Test fun `visual writer and temporary cleanup join before timeout outcome`() = runTest {
    val session = Session()
    val events = mutableListOf<String>()
    session.screenshot = {
      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable) {
          delay(100)
          events += "writer joined"
        }
      }
    }
    val inspector = InspectorAgent({ _, _, _ -> error("tree") }, { _, _, _, _ -> error("late model") })
    val evaluator = ConditionEvaluator(
      session,
      inspector,
      temporaryScreenshot = { inspect ->
        try {
          inspect(Path.of("owned.png"))
        } finally {
          events += "temporary deleted"
        }
      },
      verifyScreenshot = {},
    )
    val result = ConditionWaiter(evaluator) { testScheduler.currentTime * 1_000_000 }.await("visually ready", 2.seconds)
    events += "outcome"
    assertThat(result.satisfied).isEqualTo(false)
    assertThat(result.elapsedMs).isEqualTo(2100)
    assertThat(events).isEqualTo(listOf("writer joined", "temporary deleted", "outcome"))
  }

  @Test fun `image verification expiry prevents inspection and late model true never completes a check`() = runTest {
    for (stage in listOf("verification", "model")) {
      val session = Session()
      var inspected = false
      val inspector = InspectorAgent({ _, _, _ -> error("tree") }, { _, _, _, _ ->
        inspected = true
        withContext(NonCancellable) { delay(3.seconds) }
        inspectionReply("""{"passed":true,"reasoning":"too late"}""")
      })
      val evaluator = ConditionEvaluator(
        session,
        inspector,
        temporaryScreenshot = { it(Path.of("virtual.png")) },
        verifyScreenshot = { if (stage == "verification") delay(3.seconds) },
      )
      val result = ConditionWaiter(evaluator) { testScheduler.currentTime * 1_000_000 }.await("visually ready", 3.seconds)
      assertThat(result.satisfied).isEqualTo(false)
      assertThat(result.checks).isEqualTo(0)
      assertThat(inspected).isEqualTo(stage == "model")
    }
  }

  @Test fun `shared deadline checkpoints interrupt literal focus and rendered content inner work`() = runTest {
    val wide = HierarchyNode((0 until 1000).associate { "text$it" to "value$it" })
    val focus = HierarchyNode(
      children = (0 until 50).map { index ->
        HierarchyNode(children = listOf(HierarchyNode(mapOf("text" to if (index % 2 == 0) "needle" else "other"), if (index % 2 != 0) setOf("focused") else emptySet())))
      },
    )
    for ((threshold, work) in listOf<Pair<Int, (() -> Unit) -> Unit>>(
      50 to { checkpoint -> wide.containsText("absent", checkpoint = checkpoint) },
      400 to { checkpoint -> me.chrisbanes.verity.core.hierarchy.FocusDetector.containsFocused(focus, "needle", checkpoint) },
      50 to { checkpoint -> me.chrisbanes.verity.core.hierarchy.HierarchyRenderer.render(wide, me.chrisbanes.verity.core.hierarchy.HierarchyFilter.CONTENT, checkpoint) },
    )) {
      var sampled = 0
      val deadline = EvaluationDeadline(
        kotlinx.coroutines.currentCoroutineContext(),
        0L,
        3.seconds,
        { if (++sampled >= threshold) 3_000_000_000 else 0L },
        {},
      )
      assertFailsWith<WaitDeadlineExceeded> { work(deadline::checkpoint) }
      assertThat(sampled).isEqualTo(threshold)
    }
  }

  @Test fun `model error first observed exactly at deadline is wait timeout`() = runTest {
    var now = 0L
    val session = Session()
    val inspector = InspectorAgent({ _, _, _ ->
      now = 3_000_000_000
      error("late callback")
    }, { _, _, _, _ -> error("visual") })
    val result = ConditionWaiter(ConditionEvaluator(session, inspector)) { now }.await("semantic", 3.seconds)
    assertThat(result.satisfied).isEqualTo(false)
    assertThat(result.checks).isEqualTo(0)
    assertThat(result.elapsedMs).isEqualTo(3000)
  }

  private fun evaluator(session: Session, treePassed: Boolean = true, rejectInspector: Boolean = false) = ConditionEvaluator(
    session,
    InspectorAgent(
      { _, _, _ -> if (rejectInspector) error("unexpected inspector") else inspectionReply("""{"passed":$treePassed,"reasoning":"tree"}""") },
      { _, _, _, _ -> inspectionReply("""{"passed":true,"reasoning":"visual"}""") },
    ),
    temporaryScreenshot = { it(Path.of("virtual.png")) },
    verifyScreenshot = {},
  )

  private class Session : DeviceSession {
    override val platform = Platform.ANDROID_MOBILE
    var tree: suspend () -> HierarchyNode = { HierarchyNode(mapOf("text" to "Settings")) }
    var screenshot: suspend () -> Unit = {}
    val budgets = mutableListOf<Duration>()
    val screenshotBudgets = mutableListOf<Duration>()
    var active = 0
    var peak = 0
    override suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode {
      budgets += timeout
      active++
      peak = maxOf(peak, active)
      try {
        return tree()
      } finally {
        active--
      }
    }
    override suspend fun captureHierarchyTree(): HierarchyNode = error("unbounded capture")
    override suspend fun captureScreenshot(output: Path, timeout: Duration) {
      screenshotBudgets += timeout
      screenshot()
    }
    override suspend fun captureScreenshot(output: Path): Unit = error("unbounded screenshot")
    override suspend fun executeFlow(yaml: String): FlowResult = error("action")
    override suspend fun executeActions(flow: ActionFlow): FlowResult = error("action")
    override suspend fun pressKey(keyName: String): Unit = error("action")
    override suspend fun shell(command: String): String = error("shell")
    override suspend fun waitForAnimationToEnd(): Unit = error("animation")
    override fun close() = Unit
  }
}
