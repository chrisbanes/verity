package me.chrisbanes.verity.core.flow

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow

class ActionFlowYamlRendererTest {
  @Test
  fun `compatibility YAML preserves app context selected sequence and defaults`() {
    val flow = ActionFlow(
      "app: #雪",
      listOf(
        Interaction.LaunchApp(), Interaction.LaunchApp(false),
        Interaction.TapOnText("Settings.*"), Interaction.TapOnId("app:id/item"),
        Interaction.LongPressOnText("Hello"), Interaction.LongPressOnFocused,
        Interaction.Scroll(Direction.DOWN), Interaction.DefaultScroll, Interaction.PullToRefresh,
        Interaction.KeyPress("BACK"), Interaction.InputText("  café  "),
        Interaction.WaitForAnimation(), Interaction.WaitForAnimation(500),
        Interaction.WaitUntilVisible(text = "Next.*", timeoutMs = 1000),
        Interaction.WaitUntilVisible(resourceId = "app:id/next", timeoutMs = 2000),
      ),
    )
    assertThat(ActionFlowYamlRenderer.render(flow)).isEqualTo(
      """
      appId: "app: #雪"
      ---
      - launchApp
      - launchApp:
          clearState: false
      - tapOn: "Settings.*"
      - tapOn:
          id: "app:id/item"
      - longPressOn: "Hello"
      - longPressOn:
          focused: true
      - swipe:
          direction: DOWN
      - scroll
      - swipe:
          direction: UP
      - pressKey: "BACK"
      - inputText: "  café  "
      - waitForAnimationToEnd
      - waitForAnimationToEnd:
          timeout: 500
      - extendedWaitUntil:
          visible:
            text: "Next.*"
          timeout: 1000
      - extendedWaitUntil:
          visible:
            id: "app:id/next"
          timeout: 2000
      """.trimIndent(),
    )
  }

  @Test
  fun `scalar quoting preserves punctuation unicode regex and control characters`() {
    val text = "  café: #雪 \"quote\" \\d+\r\n\t\b\u0000  "
    assertThat(ActionFlowYamlRenderer.render(ActionFlow("app", listOf(Interaction.InputText(text)))))
      .isEqualTo("appId: \"app\"\n---\n- inputText: \"  café: #雪 \\\"quote\\\" \\\\d+\\r\\n\\t\\b\\u0000  \"")
  }
}
