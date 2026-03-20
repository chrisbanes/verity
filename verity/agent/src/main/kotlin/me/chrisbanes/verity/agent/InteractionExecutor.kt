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

      is Interaction.TapOnText -> executeCommand("- tapOn: \"${interaction.text}\"")

      is Interaction.TapOnId -> executeCommand("- tapOn:\n    id: \"${interaction.resourceId}\"")

      // Maestro's `scroll` has no direction param (always scrolls down).
      // Use `swipe` for directional scrolling.
      is Interaction.Scroll -> executeCommand("- swipe:\n    direction: ${interaction.direction}")

      is Interaction.Swipe -> executeCommand("- swipe:\n    direction: ${interaction.direction}")

      is Interaction.LongPressOnFocused -> executeCommand("- longPressOn:\n    focused: true")

      is Interaction.LongPressOnText -> executeCommand("- longPressOn: \"${interaction.text}\"")

      // Pull-to-refresh is a swipe down from near the top
      Interaction.PullToRefresh -> executeCommand("- swipe:\n    direction: UP")
    }
    session.waitForAnimationToEnd()
  }

  private suspend fun executeCommand(command: String) {
    session.executeFlow("appId: $appId\n---\n$command")
  }
}
