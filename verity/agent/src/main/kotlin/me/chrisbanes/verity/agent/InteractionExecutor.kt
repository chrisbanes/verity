package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.device.DeviceSession

class InteractionExecutor(
  private val session: DeviceSession,
  private val appId: String,
) {

  suspend fun execute(interaction: Interaction) {
    when (interaction) {
      is Interaction.KeyPress -> {
        session.pressKey(interaction.keyName)
        session.waitForAnimationToEnd()
        return
      }

      is Interaction.TapOnText -> executeCommand("- tapOn:\n    text: \"${interaction.text}\"")

      is Interaction.TapOnId -> executeCommand("- tapOn:\n    id: \"${interaction.resourceId}\"")

      is Interaction.Scroll -> executeCommand("- scroll:\n    direction: ${interaction.direction}")

      is Interaction.Swipe -> executeCommand("- swipe:\n    direction: ${interaction.direction}")

      is Interaction.LongPressOnFocused -> executeCommand("- longPressOn:\n    focused: true")

      is Interaction.LongPressOnText ->
        executeCommand("- longPressOn:\n    text: \"${interaction.text}\"")

      Interaction.PullToRefresh -> executeCommand("- scroll:\n    direction: UP")
    }
    session.waitForAnimationToEnd()
  }

  private suspend fun executeCommand(command: String) {
    session.executeFlow("appId: $appId\n---\n$command")
  }
}
