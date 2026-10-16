package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.hierarchy.HierarchyNode
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
  /** Returns the settled hierarchy after a key press that settled on stability, else null. */
  suspend fun execute(interaction: Interaction, visual: Boolean = false): HierarchyNode? {
    val flow = ActionFlow(appId, listOf(interaction))
    validateActionFlow(flow, session)
    when (interaction) {
      is Interaction.KeyPress -> return session.pressAndSettle(focusObserver, interaction.isDirection(), visual = visual) {
        session.pressKey(interaction.keyName)
      }.tree

      is Interaction.KeyCode -> return session.pressAndSettle(focusObserver, interaction.isDirection(), visual = visual) {
        session.pressKey(interaction.keycode, interaction.longPress)
      }.tree

      else -> {
        val result = session.executeActions(flow)
        if (!result.success) throw InteractionExecutionFailure(result)
      }
    }
    if (interaction !is Interaction.WaitForAnimation && interaction !is Interaction.WaitUntilVisible) {
      session.waitForAnimationToEnd()
    }
    return null
  }
}
