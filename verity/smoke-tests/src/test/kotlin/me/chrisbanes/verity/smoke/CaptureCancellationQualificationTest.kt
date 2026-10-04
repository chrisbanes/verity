package me.chrisbanes.verity.smoke

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CaptureCancellationQualificationTest {
  @Test fun `explicit caller stop records its body cause and complete join`() = runTest {
    var active = 0
    val capture = recordCapture {
      active++
      try {
        awaitCancellation()
      } finally {
        active--
      }
    }
    capture.cancelFromCallerAndVerify()
    assertThat(active).isEqualTo(0)
    assertThat(capture.job.isCompleted).isEqualTo(true)
  }

  @Test fun `prior owned expiry foreign cancellation and normal completion are rejected`() = runTest {
    val normal = recordCapture { HierarchyNode() }
    assertFailure { normal.cancelFromCallerAndVerify() }.isInstanceOf<AssertionError>()
    val foreign = recordCapture { throw CancellationException("foreign") }
    assertFailure { foreign.cancelFromCallerAndVerify() }.isInstanceOf<AssertionError>()
    val owned = recordCapture {
      withTimeout(5) {
        delay(10)
        HierarchyNode()
      }
    }
    advanceTimeBy(5)
    runCurrent()
    assertThat(owned.job.isCompleted).isEqualTo(true)
    assertFailure { owned.cancelFromCallerAndVerify() }.isInstanceOf<AssertionError>()
  }

  @Test fun `owned expiry after worker sample but before caller stop is rejected`() = runTest {
    val sampled = CompletableDeferred<Unit>()
    var active = 0
    val capture = recordCapture {
      active++
      try {
        withTimeout(5) {
          sampled.complete(Unit)
          delay(10)
          HierarchyNode()
        }
      } finally {
        active--
      }
    }
    sampled.await()
    advanceTimeBy(5)
    runCurrent()
    assertFailure { capture.cancelFromCallerAndVerify() }.isInstanceOf<AssertionError>()
    assertThat(active).isEqualTo(0)
  }

  @Test fun `foreign body failure after caller stop cannot use Deferred terminal cause`() = runTest {
    val capture = recordCapture {
      try {
        awaitCancellation()
      } catch (e: CancellationException) {
        throw CancellationException("foreign cleanup failure")
      }
    }
    assertFailure { capture.cancelFromCallerAndVerify() }.isInstanceOf<AssertionError>()
    assertThat(capture.bodyFailure.await()?.message).isEqualTo("foreign cleanup failure")
    assertThat(capture.job.isCompleted).isEqualTo(true)
  }

  @Test fun `foreign cancellation wrapping requested cause cannot earn credit`() = runTest {
    val capture = recordCapture {
      try {
        awaitCancellation()
      } catch (e: CancellationException) {
        throw CancellationException("foreign wrapper").also { it.initCause(e) }
      }
    }
    assertFailure { capture.cancelFromCallerAndVerify() }.isInstanceOf<AssertionError>()
    assertThat(capture.job.isCompleted).isEqualTo(true)
  }

  @Test fun `ordinary body error wrapping requested cancellation cannot earn credit`() = runTest {
    supervisorScope {
      val capture = recordCapture {
        try {
          awaitCancellation()
        } catch (e: CancellationException) {
          throw IllegalStateException("ordinary wrapper", e)
        }
      }
      assertFailure { capture.cancelFromCallerAndVerify() }.isInstanceOf<AssertionError>()
      assertThat(capture.bodyFailure.await()?.javaClass).isEqualTo(IllegalStateException::class.java)
      assertThat(capture.job.isCompleted).isEqualTo(true)
    }
  }
}
