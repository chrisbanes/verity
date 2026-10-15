package me.chrisbanes.verity.agent

import kotlin.time.Duration.Companion.seconds
import me.chrisbanes.verity.core.hierarchy.FocusObservation
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FocusCaptureResult
import me.chrisbanes.verity.device.FocusChangeObserver

private val FOCUS_BUDGET = 2.seconds
private val DIRECTION_KEY_NAMES = setOf("remote dpad up", "remote dpad down", "remote dpad left", "remote dpad right")
private val DIRECTION_KEYCODES = setOf(19, 20, 21, 22)

internal fun Interaction.KeyPress.isDirection() = keyName.lowercase() in DIRECTION_KEY_NAMES

internal fun Interaction.KeyCode.isDirection() = !longPress && keycode in DIRECTION_KEYCODES

/**
 * Runs [press] exactly once, then settles. For a [direction] press on Android TV, a focus change
 * replaces the animation wait; no change, or any baseline/observation failure, falls back to it.
 * A [baseline] from the previous press's settle skips the baseline capture. Returns the focus
 * observed after a change, for the next press in the same run, else null.
 * shortcut: a press that changes nothing costs the 2s budget plus the fallback wait.
 */
internal suspend fun DeviceSession.pressAndSettle(
  focusObserver: FocusChangeObserver,
  direction: Boolean,
  baseline: FocusObservation? = null,
  press: suspend () -> Unit,
): FocusObservation? {
  val before = when {
    !direction || platform != Platform.ANDROID_TV -> null
    baseline != null -> baseline
    else -> (focusObserver.capture(this, FOCUS_BUDGET) as? FocusCaptureResult.Captured)?.observation
  }
  press()
  if (before != null) {
    val result = focusObserver.awaitChange(this, before, FOCUS_BUDGET)
    if (result.changed) return result.after
  }
  waitForAnimationToEnd()
  return null
}
