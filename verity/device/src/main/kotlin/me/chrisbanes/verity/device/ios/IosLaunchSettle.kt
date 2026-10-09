package me.chrisbanes.verity.device.ios

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.device.CaptureDeadlineExceededException

private val IOS_LAUNCH_SETTLE_TIMEOUT = 30.seconds
private val IOS_LAUNCH_SETTLE_POLL = 250.milliseconds

/**
 * After `launchApp`, the XCTest hierarchy can still be SpringBoard's switcher card for [appId]
 * for several seconds while the screen already shows the app. Polls bounded captures until that
 * card is gone or [timeout] passes; later steps then report whatever they observe. Failed captures
 * are retried within the budget. The `card:` identifier is private SpringBoard accessibility state
 * observed on iOS 27; if it changes, this silently stops waiting.
 */
internal suspend fun awaitIosLaunchSettled(
  appId: String,
  capture: suspend (Duration) -> HierarchyNode,
  timeout: Duration = IOS_LAUNCH_SETTLE_TIMEOUT,
  poll: Duration = IOS_LAUNCH_SETTLE_POLL,
  timeSource: TimeSource = TimeSource.Monotonic,
) {
  val card = "card:$appId:"
  val deadline = timeSource.markNow() + timeout
  while (true) {
    val remaining = -deadline.elapsedNow()
    if (!remaining.isPositive()) return
    try {
      if (!capture(remaining).hasLaunchCard(card)) return
    } catch (e: CaptureDeadlineExceededException) {
      // The capture spent the remaining budget; only genuine caller cancellation propagates.
      currentCoroutineContext().ensureActive()
      return
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // The runner can fail a request mid-launch; retry until the deadline.
    }
    delay(poll)
  }
}

private fun HierarchyNode.hasLaunchCard(prefix: String): Boolean = attributes["resource-id"]?.startsWith(prefix) == true || children.any { it.hasLaunchCard(prefix) }
