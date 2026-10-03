package me.chrisbanes.verity.core.flow

import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow

/** Compatibility output only. Rendered YAML is never an internal execution input. */
object ActionFlowYamlRenderer {
  fun render(flow: ActionFlow): String {
    flow.validate()
    return buildString {
      append("appId: ${scalar(flow.appId)}\n---")
      if (flow.actions.isEmpty()) append("\n[]")
      for (action in flow.actions) {
        append('\n')
        append(
          when (action) {
            is Interaction.LaunchApp -> action.clearState?.let { "- launchApp:\n    clearState: $it" } ?: "- launchApp"

            is Interaction.KeyPress -> "- pressKey: ${scalar(action.keyName)}"

            is Interaction.TapOnText -> "- tapOn: ${scalar(action.text)}"

            is Interaction.TapOnId -> "- tapOn:\n    id: ${scalar(action.resourceId)}"

            is Interaction.Scroll -> "- swipe:\n    direction: ${action.direction}"

            is Interaction.Swipe -> "- swipe:\n    direction: ${action.direction}"

            Interaction.DefaultScroll -> "- scroll"

            Interaction.LongPressOnFocused -> "- longPressOn:\n    focused: true"

            is Interaction.LongPressOnText -> "- longPressOn: ${scalar(action.text)}"

            Interaction.PullToRefresh -> "- swipe:\n    direction: UP"

            is Interaction.InputText -> "- inputText: ${scalar(action.text)}"

            is Interaction.WaitForAnimation -> action.timeoutMs?.let { "- waitForAnimationToEnd:\n    timeout: $it" }
              ?: "- waitForAnimationToEnd"

            is Interaction.WaitUntilVisible -> {
              val selector = action.text?.let { "text: ${scalar(it)}" } ?: "id: ${scalar(checkNotNull(action.resourceId))}"
              "- extendedWaitUntil:\n    visible:\n      $selector\n    timeout: ${action.timeoutMs}"
            }
          },
        )
      }
    }
  }

  private fun scalar(value: String): String = buildString {
    append('"')
    for (char in value) {
      append(
        when (char) {
          '\\' -> "\\\\"

          '"' -> "\\\""

          '\n' -> "\\n"

          '\r' -> "\\r"

          '\t' -> "\\t"

          '\b' -> "\\b"

          else -> if (char < ' ' || char in '\u007f'..'\u009f' || char == '\u2028' || char == '\u2029') {
            "\\u${char.code.toString(16).padStart(4, '0')}"
          } else {
            char.toString()
          }
        },
      )
    }
    append('"')
  }
}
