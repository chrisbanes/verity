package me.chrisbanes.verity.agent

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import me.chrisbanes.verity.device.CaptureDeadlineExceededException

/** Serial read-only checks sharing one monotonic budget, including their joined cleanup. */
class ConditionWaiter(
  private val evaluator: ConditionEvaluator,
  private val nowNanos: () -> Long = System::nanoTime,
) {
  suspend fun await(
    condition: String,
    timeout: Duration = 20.seconds,
    context: InspectionContext = InspectionContext(),
  ): WaitEvaluation {
    require(timeout.isPositive() && timeout.isFinite()) { "Wait timeout must be positive and finite" }
    val parent = currentCoroutineContext()
    val started = nowNanos()
    var checks = 0
    var last: ConditionEvaluation? = null
    val modelFailure = AtomicReference<ModelFailureException?>()
    fun elapsed() = (nowNanos() - started).nanoseconds
    val observeFailure: (ModelFailureException) -> Unit = { failure ->
      if (elapsed() < timeout) modelFailure.compareAndSet(null, failure)
    }
    var satisfied = false
    try {
      satisfied = withTimeoutOrNull(timeout) {
        val deadline = EvaluationDeadline(currentCoroutineContext(), started, timeout, nowNanos, observeFailure)
        while (true) {
          deadline.checkpoint()
          val evaluation = evaluator.evaluate(condition, context, deadline, checks)
          deadline.checkpoint()
          last = evaluation
          checks++
          if (evaluation.verdict.passed) return@withTimeoutOrNull true
          delay(1.seconds)
        }
        @Suppress("UNREACHABLE_CODE")
        false
      } == true
    } catch (expiry: WaitDeadlineExceeded) {
      // Only our explicit monotonic checkpoint owns this signal.
    } catch (expiry: CaptureDeadlineExceededException) {
      // An early device-owned expiry is an execution failure, not a false condition.
      if (elapsed() < timeout) {
        parent.ensureActive()
        throw IllegalStateException(expiry.message, expiry)
      }
    } catch (failure: ModelFailureException) {
      parent.ensureActive()
      if (elapsed() < timeout) throw modelFailure.get() ?: failure
      // A callback failure first observed at/after expiry cannot replace wait timeout.
    } catch (cancellation: CancellationException) {
      parent.ensureActive()
      throw cancellation
    }
    parent.ensureActive()
    modelFailure.get()?.let { throw it }
    return WaitEvaluation(satisfied && elapsed() < timeout, checks, elapsed().inWholeMilliseconds, last)
  }
}

data class WaitEvaluation(
  val satisfied: Boolean,
  val checks: Int,
  val elapsedMs: Long,
  val lastEvaluation: ConditionEvaluation?,
)

internal class WaitDeadlineExceeded : CancellationException("Wait evaluation deadline expired")

internal class EvaluationDeadline(
  private val context: CoroutineContext,
  private val started: Long,
  private val timeout: Duration,
  private val nowNanos: () -> Long,
  val onModelFailure: (ModelFailureException) -> Unit,
) {
  fun checkpoint() {
    context.ensureActive()
    if ((nowNanos() - started).nanoseconds >= timeout) throw WaitDeadlineExceeded()
  }

  fun remaining(): Duration {
    checkpoint()
    val remaining = timeout - (nowNanos() - started).nanoseconds
    if (!remaining.isPositive()) throw WaitDeadlineExceeded()
    return remaining
  }
}
