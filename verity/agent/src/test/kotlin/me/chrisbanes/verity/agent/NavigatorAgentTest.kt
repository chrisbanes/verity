package me.chrisbanes.verity.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfigBase
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.model.Platform

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

  @Test
  fun `generate builds prompts, invokes executor, and strips code fences`() = runTest {
    var capturedSystemPrompt = ""
    var capturedUserMessage = ""
    val agent = NavigatorAgent(
      bundledContext = "Bundled context",
      agentFactory = { systemPrompt ->
        capturedSystemPrompt = systemPrompt
        FakeTextAgent { userMessage ->
          capturedUserMessage = userMessage
          "```yaml\nappId: com.example.app\n---\n- launchApp\n```"
        }
      },
    )

    val result = agent.generate(
      actions = listOf("Launch the app"),
      appId = "com.example.app",
      platform = Platform.ANDROID_TV,
      injectedContext = "Injected context",
    )

    assertThat(capturedSystemPrompt).contains("Bundled context")
    assertThat(capturedSystemPrompt).contains("Injected context")
    assertThat(capturedUserMessage).contains("Launch the app")
    assertThat(result).doesNotContain("```")
    assertThat(result).contains("appId: com.example.app")
  }

  private class FakeTextAgent(
    private val responder: suspend (String) -> String,
  ) : AIAgent<String, String> {
    override val id: String = "fake-agent"
    override val agentConfig: AIAgentConfigBase = object : AIAgentConfigBase {
      override val prompt: Prompt = Prompt.Empty
      override val model: LLModel = LLModel(provider = LLMProvider.OpenAI, id = "fake")
    }

    override suspend fun getState(): AIAgent.Companion.State<String> = AIAgent.Companion.State.NotStarted()
    override suspend fun run(agentInput: String): String = responder(agentInput)
    override suspend fun close() = Unit
  }
}
