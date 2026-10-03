package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.validateActionFlow

class InteractionExecutor(
  private val session: DeviceSession,
  private val appId: String,
) {
  suspend fun execute(interaction: Interaction) {
    val flow = ActionFlow(appId, listOf(interaction))
    validateActionFlow(flow)
    if (interaction is Interaction.KeyPress) {
      session.pressKey(interaction.keyName)
    } else {
      val result = session.executeActions(flow)
      if (!result.success) throw InteractionExecutionFailure(result)
    }
    if (interaction !is Interaction.WaitForAnimation && interaction !is Interaction.WaitUntilVisible) {
      session.waitForAnimationToEnd()
    }
  }
}
