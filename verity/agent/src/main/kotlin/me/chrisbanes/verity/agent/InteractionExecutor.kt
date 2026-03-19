package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.device.DeviceSession

class InteractionExecutor(private val session: DeviceSession) {

  suspend fun execute(interaction: Interaction) {
    when (interaction) {
      is Interaction.KeyPress -> session.pressKey(interaction.keyName)

      is Interaction.TapOnText -> session.executeFlow("- tapOn:\n    text: \"${interaction.text}\"")

      is Interaction.TapOnId ->
        session.executeFlow("- tapOn:\n    id: \"${interaction.resourceId}\"")

      is Interaction.Scroll ->
        session.executeFlow("- scroll:\n    direction: ${interaction.direction}")

      is Interaction.Swipe ->
        session.executeFlow("- swipe:\n    direction: ${interaction.direction}")

      is Interaction.LongPressOnFocused ->
        session.executeFlow("- longPressOn:\n    focused: true")

      is Interaction.LongPressOnText ->
        session.executeFlow("- longPressOn:\n    text: \"${interaction.text}\"")

      Interaction.PullToRefresh -> session.executeFlow("- scroll:\n    direction: UP")
    }
    session.waitForAnimationToEnd()
  }
}
