package me.chrisbanes.verity.device

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import me.chrisbanes.verity.core.hierarchy.FocusObservation
import me.chrisbanes.verity.core.hierarchy.hasFocusChanged
import me.chrisbanes.verity.core.hierarchy.observeFocus

sealed interface FocusCaptureResult {
  data class Captured(val observation: FocusObservation) : FocusCaptureResult
  data object TimedOut : FocusCaptureResult
  data class Failed(val cause: Exception) : FocusCaptureResult
}

data class FocusChangeResult(
  val changed: Boolean,
  val timedOut: Boolean,
  val elapsedMs: Long,
  val after: FocusObservation?,
  val captureError: Exception? = null,
)

/** Serial polling of complete captures; never owns the session or its runner lifecycle. */
class FocusChangeObserver(private val nowNanos: () -> Long = System::nanoTime) {
  suspend fun capture(session: DeviceSession, timeout: Duration): FocusCaptureResult {
    require(timeout.isPositive() && timeout.isFinite())
    val parent = currentCoroutineContext()
    val start = nowNanos()
    return try {
      val observation = withTimeoutOrNull(timeout) {
        val context = currentCoroutineContext()
        val checkpoint = {
          context.ensureActive()
          if (nowNanos() - start >= timeout.inWholeNanoseconds) throw HierarchyCaptureTimeoutException()
        }
        try {
          val tree = session.captureHierarchyTree(timeout)
          checkpoint()
          observeFocus(tree, checkpoint).also { checkpoint() }
        } catch (e: Exception) {
          context.ensureActive()
          throw e
        }
      }
      parent.ensureActive()
      if (observation == null || nowNanos() - start >= timeout.inWholeNanoseconds) {
        FocusCaptureResult.TimedOut
      } else {
        FocusCaptureResult.Captured(observation)
      }
    } catch (e: HierarchyCaptureTimeoutException) {
      parent.ensureActive()
      FocusCaptureResult.TimedOut
    } catch (e: CancellationException) {
      parent.ensureActive()
      throw e
    } catch (e: Exception) {
      parent.ensureActive()
      FocusCaptureResult.Failed(e)
    }
  }

  /** Begin immediately after successful action; baseline acquisition has its own budget. */
  suspend fun awaitChange(session: DeviceSession, before: FocusObservation, timeout: Duration): FocusChangeResult {
    require(timeout.isPositive() && timeout.isFinite())
    val parent = currentCoroutineContext()
    val start = nowNanos()
    val budget = timeout.inWholeNanoseconds
    var after: FocusObservation? = null
    fun elapsed() = (nowNanos() - start).coerceAtLeast(0)
    fun result(changed: Boolean = false, timedOut: Boolean = false, error: Exception? = null) = FocusChangeResult(changed, timedOut, elapsed() / 1_000_000, after, error)
    while (true) {
      parent.ensureActive()
      val remaining = budget - elapsed()
      if (remaining <= 0) return result(timedOut = true)
      when (val captured = capture(session, remaining.nanoseconds)) {
        FocusCaptureResult.TimedOut -> return result(timedOut = true)

        is FocusCaptureResult.Failed -> return result(error = captured.cause)

        is FocusCaptureResult.Captured -> {
          try {
            val checkpoint = {
              parent.ensureActive()
              if (elapsed() >= budget) throw HierarchyCaptureTimeoutException()
            }
            val changed = hasFocusChanged(before, captured.observation, checkpoint)
            checkpoint()
            after = captured.observation
            if (changed) return result(changed = true)
          } catch (e: HierarchyCaptureTimeoutException) {
            parent.ensureActive()
            return result(timedOut = true)
          }
        }
      }
      val remainingDelay = budget - elapsed()
      if (remainingDelay <= 0) return result(timedOut = true)
      delay(minOf(100.milliseconds, remainingDelay.nanoseconds))
    }
  }
}
