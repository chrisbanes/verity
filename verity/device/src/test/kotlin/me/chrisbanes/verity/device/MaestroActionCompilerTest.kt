package me.chrisbanes.verity.device

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.yaml.FlowParseException
import maestro.orchestra.yaml.YamlCommandReader
import me.chrisbanes.verity.core.flow.ActionFlowYamlRenderer
import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow

class MaestroActionCompilerTest {
  @Test
  fun `typed app configuration and bare launch match canonical Maestro reader`() = runTest {
    val expected = withContext(Dispatchers.IO) {
      val path = Files.createTempFile("verity-test-oracle-", ".yaml")
      try {
        Files.writeString(path, "appId: com.example.app\n---\n- launchApp")
        YamlCommandReader.readCommands(path).map { it.asCommand() }
      } finally {
        Files.deleteIfExists(path)
      }
    }
    val actual = MaestroActionCompiler.compile(ActionFlow("com.example.app", listOf(Interaction.LaunchApp())))
    assertThat(actual.map { it.asCommand() }).isEqualTo(expected)
  }

  @Test
  fun `complete typed vocabulary matches canonical command semantics without source locations`() = runTest {
    val actions = listOf(
      Interaction.LaunchApp(), Interaction.LaunchApp(false),
      Interaction.KeyPress("BACK"), Interaction.TapOnText("Settings.*"), Interaction.TapOnId("example:id/item"),
      Interaction.LongPressOnText("Hello.*"), Interaction.LongPressOnFocused,
      Interaction.Swipe(Direction.UP), Interaction.Swipe(Direction.DOWN),
      Interaction.Swipe(Direction.LEFT), Interaction.Swipe(Direction.RIGHT),
      Interaction.Scroll(Direction.DOWN), Interaction.PullToRefresh, Interaction.DefaultScroll,
      Interaction.InputText("  café: #雪 \"quote\" \\d+\r\n  "), Interaction.InputText(""),
      Interaction.WaitForAnimation(), Interaction.WaitForAnimation(500),
      Interaction.WaitUntilVisible(text = "Next.*", timeoutMs = 1000),
      Interaction.WaitUntilVisible(resourceId = "example:id/next", timeoutMs = 2000),
    )
    val yaml = """
      appId: com.example.app
      ---
      - launchApp
      - launchApp:
          clearState: false
      - pressKey: BACK
      - tapOn: "Settings.*"
      - tapOn:
          id: "example:id/item"
      - longPressOn: "Hello.*"
      - longPressOn:
          focused: true
      - swipe:
          direction: UP
      - swipe:
          direction: DOWN
      - swipe:
          direction: LEFT
      - swipe:
          direction: RIGHT
      - swipe:
          direction: DOWN
      - swipe:
          direction: UP
      - scroll
      - inputText: "  café: #雪 \"quote\" \\d+\r\n  "
      - inputText: ""
      - waitForAnimationToEnd
      - waitForAnimationToEnd:
          timeout: 500
      - extendedWaitUntil:
          visible:
            text: "Next.*"
          timeout: 1000
      - extendedWaitUntil:
          visible:
            id: "example:id/next"
          timeout: 2000
    """.trimIndent()
    val expected = withContext(Dispatchers.IO) {
      val path = Files.createTempFile("verity-test-oracle-", ".yaml")
      try {
        Files.writeString(path, yaml)
        YamlCommandReader.readCommands(path).map { it.asCommand() }
      } finally {
        Files.deleteIfExists(path)
      }
    }
    val actual = MaestroActionCompiler.compile(ActionFlow("com.example.app", actions)).map { it.asCommand() }
    assertThat(actual.size).isEqualTo(expected.size)
    expected.forEachIndexed { index, command -> assertThat(actual[index], "command $index").isEqualTo(command) }
  }

  @Test
  fun `canonical reader distinguishes missing command section from explicit empty list`() = runTest {
    val outcomes = withContext(Dispatchers.IO) {
      listOf("appId: app\n---", "appId: app\n---\n[]").map { yaml ->
        val path = Files.createTempFile("verity-test-empty-oracle-", ".yaml")
        try {
          Files.writeString(path, yaml)
          try {
            YamlCommandReader.readCommands(path).size.toString()
          } catch (error: SyntaxError) {
            if (error.message.orEmpty().startsWith("Commands Section Required")) "Commands Section Required" else throw error
          } catch (error: FlowParseException) {
            error.title
          }
        } finally {
          Files.deleteIfExists(path)
        }
      }
    }
    assertThat(outcomes).isEqualTo(listOf("Commands Section Required", "1"))
  }

  @Test
  fun `escaped selectors and rendered empty list retain canonical semantics`() = runTest {
    val selector = "café \"雪\" \\d+: #\r\n"
    val flow = ActionFlow(
      "app",
      listOf(
        Interaction.TapOnText(selector),
        Interaction.TapOnId("app:id/item\\d+"),
        Interaction.LongPressOnText(selector),
      ),
    )
    val yaml = """
      appId: app
      ---
      - tapOn: "café \"雪\" \\d+: #\r\n"
      - tapOn:
          id: "app:id/item\\d+"
      - longPressOn: "café \"雪\" \\d+: #\r\n"
    """.trimIndent()
    for ((selected, rendered) in listOf(flow to yaml, ActionFlow("app", emptyList()) to ActionFlowYamlRenderer.render(ActionFlow("app", emptyList())))) {
      val expected = withContext(Dispatchers.IO) {
        val path = Files.createTempFile("verity-test-scalar-oracle-", ".yaml")
        try {
          Files.writeString(path, rendered)
          YamlCommandReader.readCommands(path).map { it.asCommand() }
        } finally {
          Files.deleteIfExists(path)
        }
      }
      assertThat(MaestroActionCompiler.compile(selected).map { it.asCommand() }).isEqualTo(expected)
    }
  }
}
