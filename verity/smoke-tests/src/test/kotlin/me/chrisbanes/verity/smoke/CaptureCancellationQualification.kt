package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isTrue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

/** Record the body outcome, which may differ from a Deferred's first cancellation cause. */
internal class RecordedCapture(
  val job: Deferred<HierarchyNode>,
  val bodyFailure: CompletableDeferred<Throwable?>,
)

internal fun CoroutineScope.recordCapture(block: suspend () -> HierarchyNode): RecordedCapture {
  val failure = CompletableDeferred<Throwable?>()
  val job = async(start = CoroutineStart.UNDISPATCHED) {
    try {
      block().also { failure.complete(null) }
    } catch (e: Throwable) {
      failure.complete(e)
      throw e
    }
  }
  return RecordedCapture(job, failure)
}

private class SmokeCallerCancellation : CancellationException("Smoke requested caller cancellation")

/** A prior expiry/foreign failure or normal completion must never earn caller-stop credit. */
internal suspend fun RecordedCapture.cancelFromCallerAndVerify() {
  val caller = SmokeCallerCancellation()
  job.cancel(caller)
  job.join()
  assertThat(bodyFailure.isCompleted, "Capture body must finish before cancellation qualification").isTrue()
  val observed = bodyFailure.await()
  // Coroutine stack recovery may copy this exact exception type and retain its original cause.
  val requested = observed === caller ||
    (observed is SmokeCallerCancellation && observed.message == caller.message && observed.cause === caller)
  assertThat(requested, "Expected caller cancellation, observed body outcome: ${observed?.javaClass?.name ?: "normal completion"}").isTrue()
}
