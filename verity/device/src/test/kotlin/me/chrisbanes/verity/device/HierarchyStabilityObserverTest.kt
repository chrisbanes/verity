package me.chrisbanes.verity.device

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

class HierarchyStabilityObserverTest {
  private fun tree(text: String) = HierarchyNode(attributes = mapOf("resource-id" to "player", "text" to text))

  private class Script(val read: suspend (Int) -> HierarchyNode) : DeviceSession by FakeDeviceSession() {
    var captures = 0
    override suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode = read(captures++)
  }

  private fun TestScope.observer() = HierarchyStabilityObserver { testScheduler.currentTime * 1_000_000 }

  private fun TestScope.await(script: Script, budget: Duration = 2.seconds) = async { observer().await(script, budget) }

  @Test fun `two equal captures are stable`() = runTest {
    val script = Script { tree("A") }
    val result = await(script).await()
    assertThat(result).isEqualTo(StableHierarchyResult(stable = true, timedOut = false, elapsedMs = 100, tree = tree("A")))
    assertThat(script.captures).isEqualTo(2)
  }

  @Test fun `a transition that settles after several polls is not reported early`() = runTest {
    val sequence = listOf("A", "B", "C", "C")
    val script = Script { tree(sequence[it]) }
    val result = await(script).await()
    assertThat(result.stable).isEqualTo(true)
    assertThat(result.tree).isEqualTo(tree("C"))
    assertThat(script.captures).isEqualTo(4)
  }

  @Test fun `a tree that never settles times out without a tree`() = runTest {
    val script = Script { tree("Item $it") }
    val start = testScheduler.currentTime
    val result = await(script).await()
    assertThat(result.stable).isEqualTo(false)
    assertThat(result.timedOut).isEqualTo(true)
    assertThat(result.tree).isNull()
    val elapsed = testScheduler.currentTime - start
    assertThat(elapsed).isGreaterThanOrEqualTo(2000)
    assertThat(elapsed).isLessThan(2100)
  }

  @Test fun `clock text changes are stable`() = runTest {
    val script = Script { tree("00:${10 + it}") }
    val result = await(script).await()
    assertThat(result.stable).isEqualTo(true)
    assertThat(script.captures).isEqualTo(2)
  }

  @Test fun `capture failures are reported, not timed out`() = runTest {
    val failed = await(Script { error("transport") }).await()
    assertThat(failed.stable).isEqualTo(false)
    assertThat(failed.timedOut).isEqualTo(false)
    assertThat(failed.captureError).isNotNull().isInstanceOf<IllegalStateException>()
    val unsupported = observer().await(FakeDeviceSession(), 2.seconds)
    assertThat(unsupported.captureError).isNotNull().isInstanceOf<UnsupportedOperationException>()
  }

  @Test fun `caller cancellation propagates`() = runTest {
    val entered = CompletableDeferred<Unit>()
    val job = await(
      Script {
        entered.complete(Unit)
        awaitCancellation()
      },
    )
    entered.await()
    job.cancel()
    assertFailure { job.await() }.isInstanceOf<CancellationException>()
  }

  @Test fun `non positive or infinite budgets are rejected`() = runTest {
    for (budget in listOf(Duration.ZERO, (-1).milliseconds, Duration.INFINITE)) {
      assertFailure { observer().await(Script { tree("A") }, budget) }.isInstanceOf<IllegalArgumentException>()
    }
  }
}
