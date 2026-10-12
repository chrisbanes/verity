package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.device.DeviceSession

/**
 * Executes a validated [flow] in order. Raw keycode actions cannot be Maestro commands, so each
 * maximal run of other actions becomes its own Maestro sub-flow and each keycode is pressed directly
 * between them, settling afterwards like a key press. The first failed sub-flow stops the walk.
 */
internal suspend fun DeviceSession.executeOrdered(flow: ActionFlow): FlowResult {
  if (flow.actions.none { it is Interaction.KeyCode }) return executeActions(flow)

  var result = FlowResult(success = true)
  val run = mutableListOf<Interaction>()
  suspend fun flush(): Boolean {
    if (run.isEmpty()) return true
    result = executeActions(ActionFlow(flow.appId, run.toList()))
    run.clear()
    return result.success
  }
  for (action in flow.actions) {
    if (action !is Interaction.KeyCode) {
      run += action
      continue
    }
    if (!flush()) return result
    pressKey(action.keycode, action.longPress)
    waitForAnimationToEnd()
  }
  flush()
  return result
}
