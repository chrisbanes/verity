package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.hierarchy.FocusObservation
import me.chrisbanes.verity.core.hierarchy.observeFocus
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FocusChangeObserver

/**
 * Executes a validated [flow] in order. Raw keycode actions cannot be Maestro commands, and on
 * Android TV direct key presses settle on focus change or hierarchy stability instead of a Maestro
 * animation wait, so each maximal run of other actions becomes its own Maestro sub-flow and those
 * keys are pressed directly between them, settling afterwards. A stable settle's tree supplies the
 * next press's focus baseline; [visual] keeps the animation wait for screenshot checks. A wait for
 * animation right after a direct press is replaced by its settle. The first failed sub-flow stops
 * the walk.
 */
internal suspend fun DeviceSession.executeOrdered(
  flow: ActionFlow,
  focusObserver: FocusChangeObserver = FocusChangeObserver(),
  visual: Boolean = false,
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
    val settled = when (action) {
      is Interaction.KeyCode -> pressAndSettle(focusObserver, action.isDirection(), focus, visual) { pressKey(action.keycode, action.longPress) }
      is Interaction.KeyPress -> pressAndSettle(focusObserver, direction = true, focus, visual) { pressKey(action.keyName) }
      else -> error("Unexpected direct action: $action")
    }
    focus = settled.focus ?: settled.tree?.let(::observeFocus)
    previousDirect = true
  }
  flush()
  return result
}
