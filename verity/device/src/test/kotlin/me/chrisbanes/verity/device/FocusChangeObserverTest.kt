package me.chrisbanes.verity.device

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.observeFocus

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FocusChangeObserverTest {
  private fun tree(id: String? = "a") = HierarchyNode(attributes = id?.let { mapOf("resource-id" to it) }.orEmpty(), states = if (id == null) emptySet() else setOf("focused"))
  private class Script(val scope: TestScope, val read: suspend (Int) -> HierarchyNode) : DeviceSession by FakeDeviceSession() {
    val entries = mutableListOf<Long>()
    val budgets = mutableListOf<Duration>()
    var active = 0
    var peak = 0
    override suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode {
      entries += scope.testScheduler.currentTime
      budgets += timeout
      active++
      peak = maxOf(peak, active)
      try {
        return read(entries.lastIndex)
      } finally {
        active--
      }
    }
  }
  private fun TestScope.observer() = FocusChangeObserver { testScheduler.currentTime * 1_000_000 }

  @Test fun `immediate delayed and empty transitions`() = runTest {
    for ((before, after, changed) in listOf(Triple("a", "b", true), Triple(null, "b", true), Triple("a", null, true), Triple(null, null, false))) {
      val script = Script(this) { tree(after) }
      val result = observer().awaitChange(script, observeFocus(tree(before)), 200.milliseconds)
      assertThat(result.changed).isEqualTo(changed)
      assertThat(result.timedOut).isEqualTo(!changed)
      assertThat(result.elapsedMs).isEqualTo(if (changed) 0 else 200)
      assertThat(script.active).isEqualTo(0)
      assertThat(script.peak).isEqualTo(1)
    }
    val script = Script(this) { tree(if (it == 0) "a" else "b") }
    val start = testScheduler.currentTime
    val result = observer().awaitChange(script, observeFocus(tree()), 250.milliseconds)
    assertThat(script.entries).isEqualTo(listOf(start, start + 100))
    assertThat(result.elapsedMs).isEqualTo(100)
  }

  @Test fun `completed capture precedes polling delay and final capture gets remainder`() = runTest {
    val slow = Script(this) {
      delay(40)
      tree(if (it == 0) "a" else "b")
    }
    assertThat(observer().awaitChange(slow, observeFocus(tree()), 500.milliseconds).elapsedMs).isEqualTo(180)
    assertThat(slow.entries).isEqualTo(listOf(0L, 140L))
    val capped = Script(this) { tree().copy(attributes = mapOf("resource-id" to "a", "text" to it.toString())) }
    val start = testScheduler.currentTime
    val result = observer().awaitChange(capped, observeFocus(tree()), 250.milliseconds)
    assertThat(capped.entries).isEqualTo(listOf(start, start + 100, start + 200))
    assertThat(capped.budgets).isEqualTo(listOf(250.milliseconds, 150.milliseconds, 50.milliseconds))
    assertThat(result.timedOut).isEqualTo(true)
    assertThat(result.after).isEqualTo(observeFocus(tree().copy(attributes = mapOf("resource-id" to "a", "text" to "2"))))
  }

  @Test fun `slow capture is joined with no late value and last valid observation is retained`() = runTest {
    val slow = Script(this) {
      delay(300)
      tree("b")
    }
    val result = observer().awaitChange(slow, observeFocus(tree()), 200.milliseconds)
    assertThat(result.timedOut).isEqualTo(true)
    assertThat(result.elapsedMs).isEqualTo(200)
    assertThat(result.after).isEqualTo(null)
    assertThat(slow.active).isEqualTo(0)
    val delayCap = Script(this) {
      delay(150)
      tree()
    }
    val start = testScheduler.currentTime
    assertThat(observer().awaitChange(delayCap, observeFocus(tree()), 200.milliseconds).elapsedMs).isEqualTo(200)
    assertThat(delayCap.entries).isEqualTo(listOf(start))
  }

  @Test fun `baseline budget is independent and timeout sizes are exact`() = runTest {
    for (budget in listOf(1.milliseconds, 2000.milliseconds, Int.MAX_VALUE.milliseconds)) {
      val script = Script(this) {
        delay(1)
        tree()
      }
      val baseline = observer().capture(script, budget)
      assertThat(script.budgets.single()).isEqualTo(budget)
      if (budget == 1.milliseconds) {
        assertThat(baseline).isEqualTo(FocusCaptureResult.TimedOut)
      } else {
        assertThat(baseline).isInstanceOf<FocusCaptureResult.Captured>()
        val changed = Script(this) { tree("b") }
        assertThat(observer().awaitChange(changed, observeFocus(tree()), budget).elapsedMs).isEqualTo(0)
        assertThat(changed.budgets.single()).isEqualTo(budget)
      }
    }
  }

  @Test fun `ordinary errors differ from owned and foreign cancellations`() = runTest {
    val failure = IllegalStateException("transport")
    val error = Script(this) { if (it == 0) tree() else throw failure }
    val result = observer().awaitChange(error, observeFocus(tree()), 500.milliseconds)
    assertThat(result.captureError).isNotNull().isInstanceOf<IllegalStateException>()
    assertThat(result.captureError?.message).isEqualTo("transport")
    assertThat(result.timedOut).isEqualTo(false)
    assertThat(result.after).isEqualTo(observeFocus(tree()))
    val failed = observer().capture(Script(this) { throw failure }, 200.milliseconds)
    assertThat(failed).isInstanceOf<FocusCaptureResult.Failed>()
    assertThat((failed as FocusCaptureResult.Failed).cause.message).isEqualTo("transport")
    assertThat(observer().capture(Script(this) { throw HierarchyCaptureTimeoutException() }, 200.milliseconds)).isEqualTo(FocusCaptureResult.TimedOut)
    assertFailure { observer().capture(Script(this) { throw CancellationException("foreign") }, 200.milliseconds) }.isInstanceOf<CancellationException>()
    assertFailure {
      observer().capture(
        Script(this) {
          withTimeout(1) {
            delay(2)
            tree()
          }
        },
        200.milliseconds,
      )
    }.isInstanceOf<CancellationException>()
  }

  @Test fun `caller cancellation during baseline capture and polling delay joins captures`() = runTest {
    for (baseline in listOf(true, false)) {
      val entered = CompletableDeferred<Unit>()
      val script = Script(this) {
        entered.complete(Unit)
        awaitCancellation()
      }
      val job = async { if (baseline) observer().capture(script, 2000.milliseconds) else observer().awaitChange(script, observeFocus(tree()), 2000.milliseconds) }
      entered.await()
      job.cancel()
      assertFailure { job.await() }.isInstanceOf<CancellationException>()
      assertThat(script.active).isEqualTo(0)
    }
    val script = Script(this) { tree() }
    val job = async { observer().awaitChange(script, observeFocus(tree()), 2000.milliseconds) }
    runCurrent()
    job.cancel()
    assertFailure { job.await() }.isInstanceOf<CancellationException>()
    assertThat(script.entries.size).isEqualTo(1)
    assertThat(script.active).isEqualTo(0)
  }

  @Test fun `extraction cannot accept work completed at deadline`() = runTest {
    var ticks = 0L
    val observer = FocusChangeObserver { ticks++ * 1_000_000 }
    val script = Script(this) { HierarchyNode(children = List(50) { tree("b") }) }
    val result = observer.awaitChange(script, observeFocus(tree()), 10.milliseconds)
    assertThat(result.changed).isEqualTo(false)
    assertThat(result.timedOut).isEqualTo(true)
    assertThat(result.after).isEqualTo(null)
    assertThat(script.active).isEqualTo(0)
  }
}
