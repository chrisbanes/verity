package me.chrisbanes.verity.agent

import kotlin.time.Duration.Companion.seconds
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
 * shortcut: a press that changes nothing costs the 2s budget plus the fallback wait.
 */
internal suspend fun DeviceSession.pressAndSettle(
  focusObserver: FocusChangeObserver,
  direction: Boolean,
  press: suspend () -> Unit,
) {
  val baseline = if (direction && platform == Platform.ANDROID_TV) {
    focusObserver.capture(this, FOCUS_BUDGET) as? FocusCaptureResult.Captured
  } else {
    null
  }
  press()
  if (baseline != null && focusObserver.awaitChange(this, baseline.observation, FOCUS_BUDGET).changed) return
  waitForAnimationToEnd()
}
