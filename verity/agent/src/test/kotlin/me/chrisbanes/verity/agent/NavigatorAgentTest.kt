package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import me.chrisbanes.verity.core.model.Platform
import kotlin.test.Test

class NavigatorAgentTest {
  @Test
  fun `system prompt includes bundled Maestro basics`() {
    val prompt = NavigatorAgent.buildSystemPrompt(
      platform = Platform.ANDROID_TV,
      bundledContext = "Maestro basics: appId header, waitForAnimationToEnd, extendedWaitUntil",
      injectedContext = "",
    )
    assertThat(prompt).contains("Maestro basics")
  }

  @Test
  fun `system prompt includes platform context for Android TV`() {
    val prompt = NavigatorAgent.buildSystemPrompt(
      platform = Platform.ANDROID_TV,
      bundledContext = "Bundled context",
      injectedContext = "",
    )
    assertThat(prompt).contains("Android TV")
    assertThat(prompt).contains("D-pad")
  }

  @Test
  fun `system prompt includes platform context for iOS`() {
    val prompt = NavigatorAgent.buildSystemPrompt(
      platform = Platform.IOS,
      bundledContext = "Bundled context",
      injectedContext = "",
    )
    assertThat(prompt).contains("iOS")
  }

  @Test
  fun `system prompt appends injected context`() {
    val injectedContext = "App uses custom navigation component"
    val prompt = NavigatorAgent.buildSystemPrompt(
      platform = Platform.ANDROID_TV,
      bundledContext = "Bundled context",
      injectedContext = injectedContext,
    )
    assertThat(prompt).contains("custom navigation component")
  }

  @Test
  fun `user message formats actions as numbered list`() {
    val actions = listOf("Launch the app", "Press D-pad down", "Press select")
    val message = NavigatorAgent.buildUserMessage(actions, "com.example.app")
    assertThat(message).contains("1. Launch the app")
    assertThat(message).contains("2. Press D-pad down")
    assertThat(message).contains("3. Press select")
    assertThat(message).contains("com.example.app")
  }

  @Test
  fun `strips markdown code fences from response`() {
    val response = "```yaml\nappId: com.example\n---\n- pressKey: back\n```"
    val cleaned = NavigatorAgent.cleanResponse(response)
    assertThat(cleaned).doesNotContain("```")
    assertThat(cleaned).contains("appId: com.example")
  }

  @Test
  fun `passes through clean response unchanged`() {
    val response = "appId: com.example\n---\n- pressKey: back"
    assertThat(NavigatorAgent.cleanResponse(response)).contains("appId: com.example")
  }
}
