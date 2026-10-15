package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.hierarchy.FocusObservation
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FocusChangeObserver

/**
 * Executes a validated [flow] in order. Raw keycode actions cannot be Maestro commands, and on
 * Android TV direction key presses settle on focus change instead of a Maestro animation wait, so
 * each maximal run of other actions becomes its own Maestro sub-flow and those keys are pressed
 * directly between them, settling afterwards. A wait for animation right after a direct press is
 * replaced by its settle. The first failed sub-flow stops the walk.
 */
internal suspend fun DeviceSession.executeOrdered(
  flow: ActionFlow,
  focusObserver: FocusChangeObserver = FocusChangeObserver(),
): FlowResult {
  fun Interaction.isDirect() = this is Interaction.KeyCode || (this is Interaction.KeyPress && platform == Platform.ANDROID_TV && isDirection())
  if (flow.actions.none { it.isDirect() }) return executeActions(flow)

  var result = FlowResult(success = true)
  val run = mutableListOf<Interaction>()
  suspend fun flush(): Boolean {
    if (run.isEmpty()) return true
    result = executeActions(ActionFlow(flow.appId, run.toList()))
    run.clear()
    return result.success
  }
  var previousDirect = false
  // Focus after the last direct press; only valid while no Maestro sub-flow ran since.
  var focus: FocusObservation? = null
  for (action in flow.actions) {
    if (!action.isDirect()) {
      if (!(previousDirect && action is Interaction.WaitForAnimation)) run += action
      previousDirect = false
      continue
    }
    if (run.isNotEmpty()) focus = null
    if (!flush()) return result
    focus = when (action) {
      is Interaction.KeyCode -> pressAndSettle(focusObserver, action.isDirection(), focus) { pressKey(action.keycode, action.longPress) }
      is Interaction.KeyPress -> pressAndSettle(focusObserver, direction = true, focus) { pressKey(action.keyName) }
      else -> error("Unexpected direct action: $action")
    }
    previousDirect = true
  }
  flush()
  return result
}
