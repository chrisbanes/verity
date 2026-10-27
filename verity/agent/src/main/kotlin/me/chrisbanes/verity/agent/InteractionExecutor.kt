package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.hierarchy.FocusObservation
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FocusChangeObserver
import me.chrisbanes.verity.device.validateActionFlow

class InteractionExecutor(
  private val session: DeviceSession,
  private val appId: String,
  private val focusObserver: FocusChangeObserver = FocusChangeObserver(),
) {
  // The previous press's settled focus, so a run of presses skips each pre-press capture. Any
  // non-key interaction or a press without a settled focus leaves it null.
  private var baseline: FocusObservation? = null

  /** Returns the key press's settle, with neither focus nor tree for a non-key interaction. */
  internal suspend fun execute(interaction: Interaction, visual: Boolean = false): KeySettleResult {
    val flow = ActionFlow(appId, listOf(interaction))
    validateActionFlow(flow, session)
    val before = baseline
    baseline = null
    when (interaction) {
      is Interaction.KeyPress -> return settle(interaction.isDirection(), before, visual) { session.pressKey(interaction.keyName) }

      is Interaction.KeyCode -> return settle(interaction.isDirection(), before, visual) { session.pressKey(interaction.keycode, interaction.longPress) }

      else -> {
        val result = session.executeActions(flow)
        if (!result.success) throw InteractionExecutionFailure(result)
      }
    }
    if (interaction !is Interaction.WaitForAnimation && interaction !is Interaction.WaitUntilVisible) {
      session.waitForAnimationToEnd()
    }
    return KeySettleResult(null, null)
  }

  private suspend fun settle(direction: Boolean, before: FocusObservation?, visual: Boolean, press: suspend () -> Unit): KeySettleResult = session.pressAndSettle(focusObserver, direction, before, visual, press).also { baseline = it.settledFocus() }
}
