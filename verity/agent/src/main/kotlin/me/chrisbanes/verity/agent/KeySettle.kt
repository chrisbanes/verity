package me.chrisbanes.verity.agent

import kotlin.time.Duration.Companion.seconds
import me.chrisbanes.verity.core.hierarchy.FocusObservation
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FocusCaptureResult
import me.chrisbanes.verity.device.FocusChangeObserver

private val FOCUS_BUDGET = 2.seconds
private val STABILITY_BUDGET = 2.seconds
private val DIRECTION_KEY_NAMES = setOf("remote dpad up", "remote dpad down", "remote dpad left", "remote dpad right")
private val DIRECTION_KEYCODES = setOf(19, 20, 21, 22)

internal fun Interaction.KeyPress.isDirection() = keyName.lowercase() in DIRECTION_KEY_NAMES

internal fun Interaction.KeyCode.isDirection() = !longPress && keycode in DIRECTION_KEYCODES

/** [focus] is set after an observed focus change; [tree] is the final capture of a stable settle. */
internal data class KeySettleResult(val focus: FocusObservation?, val tree: HierarchyNode?)

/**
 * Runs [press] exactly once, then settles. A [visual] settle needs pixel stability, so it always
 * keeps the animation wait and never observes focus. Otherwise, for a [direction] press on Android
 * TV, a focus change replaces the animation wait. Any other Android TV press settles when two
 * consecutive post-press hierarchies are structurally equal; a budget expiry proceeds without a
 * wait, and a capture failure falls back to the animation wait. Other platforms keep the animation
 * wait. A [baseline] from the previous press's settle skips the baseline capture.
 * shortcut: a press that changes nothing costs the focus budget plus at least 2 captures.
 */
internal suspend fun DeviceSession.pressAndSettle(
  focusObserver: FocusChangeObserver,
  direction: Boolean,
  baseline: FocusObservation? = null,
  visual: Boolean = false,
  press: suspend () -> Unit,
): KeySettleResult {
  val before = when {
    !direction || visual || platform != Platform.ANDROID_TV -> null
    baseline != null -> baseline
    else -> (focusObserver.capture(this, FOCUS_BUDGET) as? FocusCaptureResult.Captured)?.observation
  }
  press()
  if (before != null) {
    val result = focusObserver.awaitChange(this, before, FOCUS_BUDGET)
    if (result.changed) return KeySettleResult(result.after, null)
  }
  if (platform == Platform.ANDROID_TV && !visual) {
    val stable = awaitStableHierarchy(STABILITY_BUDGET)
    if (stable.captureError == null) return KeySettleResult(null, stable.tree)
  }
  waitForAnimationToEnd()
  return KeySettleResult(null, null)
}
