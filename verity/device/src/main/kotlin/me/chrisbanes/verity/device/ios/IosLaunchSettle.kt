package me.chrisbanes.verity.device.ios

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

internal val IOS_LAUNCH_SETTLE_TIMEOUT = 30.seconds
private val IOS_LAUNCH_SETTLE_POLL = 250.milliseconds

/**
 * After `launchApp`, the XCTest hierarchy can still be SpringBoard's switcher card for [appId]
 * for several seconds while the screen already shows the app. Polls bounded captures until that
 * card is gone, returning false if it remains after [timeout].
 */
internal suspend fun awaitIosLaunchSettled(
  appId: String,
  capture: suspend (Duration) -> HierarchyNode,
  timeout: Duration = IOS_LAUNCH_SETTLE_TIMEOUT,
  poll: Duration = IOS_LAUNCH_SETTLE_POLL,
): Boolean {
  val deadline = TimeSource.Monotonic.markNow() + timeout
  while (true) {
    val remaining = -deadline.elapsedNow()
    if (!remaining.isPositive()) return false
    if (!capture(remaining).hasLaunchCard("card:$appId:")) return true
    delay(poll)
  }
}

private fun HierarchyNode.hasLaunchCard(prefix: String): Boolean = attributes["resource-id"]?.startsWith(prefix) == true || children.any { it.hasLaunchCard(prefix) }
