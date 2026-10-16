package me.chrisbanes.verity.device

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.isStructurallyEqual

/** [tree] is the final capture, set only when [stable]; a budget expiry never yields a tree. */
data class StableHierarchyResult(
  val stable: Boolean,
  val timedOut: Boolean,
  val elapsedMs: Long,
  val tree: HierarchyNode?,
  val captureError: Exception? = null,
)

/** Serial polling until two consecutive complete captures are structurally equal. */
class HierarchyStabilityObserver(private val nowNanos: () -> Long = System::nanoTime) {
  /** Begin immediately after the action; nothing captured before the call is compared. */
  suspend fun await(session: DeviceSession, budget: Duration): StableHierarchyResult {
    require(budget.isPositive() && budget.isFinite()) { "Stability budget must be positive and finite" }
    val parent = currentCoroutineContext()
    val start = nowNanos()
    val limit = budget.inWholeNanoseconds
    fun elapsed() = (nowNanos() - start).coerceAtLeast(0)
    fun result(tree: HierarchyNode? = null, timedOut: Boolean = false, error: Exception? = null) = StableHierarchyResult(tree != null, timedOut, elapsed() / 1_000_000, tree, error)
    val checkpoint = {
      parent.ensureActive()
      if (elapsed() >= limit) throw HierarchyCaptureTimeoutException()
    }
    var previous: HierarchyNode? = null
    while (true) {
      parent.ensureActive()
      val remaining = (limit - elapsed()).nanoseconds
      if (!remaining.isPositive()) return result(timedOut = true)
      try {
        val tree = withTimeoutOrNull(remaining) { session.captureHierarchyTree(remaining) } ?: return result(timedOut = true)
        checkpoint()
        if (previous != null && isStructurallyEqual(previous, tree, checkpoint)) {
          checkpoint()
          return result(tree)
        }
        previous = tree
      } catch (e: HierarchyCaptureTimeoutException) {
        parent.ensureActive()
        return result(timedOut = true)
      } catch (e: CancellationException) {
        parent.ensureActive()
        throw e
      } catch (e: Exception) {
        parent.ensureActive()
        return result(error = e)
      }
      val remainingDelay = limit - elapsed()
      if (remainingDelay <= 0) return result(timedOut = true)
      delay(minOf(100.milliseconds, remainingDelay.nanoseconds))
    }
  }
}
