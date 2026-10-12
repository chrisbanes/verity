package me.chrisbanes.verity.device

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
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
import me.chrisbanes.verity.core.model.ActionFlowInvalidReason
import me.chrisbanes.verity.core.model.InvalidActionFlowException
import me.chrisbanes.verity.core.model.Platform

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

  @Test
  fun `device-free validation accepts raw keycodes that compilation rejects`() {
    val flow = ActionFlow("app", listOf(Interaction.KeyCode(174), Interaction.TapOnText("OK")))
    validateActionFlow(flow)
    for (invalid in listOf(Interaction.KeyCode(-1), Interaction.TapOnText("["))) {
      assertFailure { validateActionFlow(ActionFlow("app", listOf(Interaction.KeyCode(174), invalid))) }
        .isInstanceOf<InvalidActionFlowException>()
    }
    assertFailure { MaestroActionCompiler.compile(flow) }
      .isInstanceOf<InvalidActionFlowException>()
      .transform { it.reason }
      .isEqualTo(ActionFlowInvalidReason.UNSUPPORTED_INTERACTION)
  }

  @Test
  fun `capability validation rejects unsupported raw keycodes and long presses`() {
    val plain = ActionFlow("app", listOf(Interaction.KeyCode(174)))
    val held = ActionFlow("app", listOf(Interaction.KeyCode(174, longPress = true)))
    val maestroOnly = ActionFlow("app", listOf(Interaction.TapOnText("OK")))

    validateActionFlow(maestroOnly, supportsRawKeycodes = false, supportsLongPress = false)
    assertUnsupported { validateActionFlow(plain, supportsRawKeycodes = false, supportsLongPress = false) }
    validateActionFlow(plain, supportsRawKeycodes = true, supportsLongPress = false)
    assertUnsupported { validateActionFlow(held, supportsRawKeycodes = true, supportsLongPress = false) }
    validateActionFlow(held, supportsRawKeycodes = true, supportsLongPress = true)

    assertUnsupported { validateActionFlow(plain, FakeDeviceSession(Platform.IOS)) }
    validateActionFlow(held, FakeDeviceSession(Platform.ANDROID_TV))
  }

  @Test
  fun `unknown key name is named only when it is safe to echo`() {
    fun messageFor(keyName: String) = assertFailure { validateActionFlow(ActionFlow("app", listOf(Interaction.KeyPress(keyName)))) }
      .isInstanceOf<InvalidActionFlowException>()
      .also { it.transform { error -> error.reason }.isEqualTo(ActionFlowInvalidReason.UNSUPPORTED_KEY) }
      .transform { it.message.orEmpty() }

    messageFor("Bookmark").also {
      it.contains("'Bookmark'")
      it.contains("keyCode")
    }
    messageFor("Book\nmark").doesNotContain("Book")
    messageFor("K".repeat(65)).doesNotContain("KKK")
  }

  private fun assertUnsupported(block: () -> Unit) {
    assertFailure(block)
      .isInstanceOf<InvalidActionFlowException>()
      .transform { it.reason }
      .isEqualTo(ActionFlowInvalidReason.UNSUPPORTED_INTERACTION)
  }
}
