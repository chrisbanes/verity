package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.ActionFlowPreparationException
import me.chrisbanes.verity.device.ActionFlowPreparationPhase

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
      executeRequest = { systemPrompt, userMessage ->
        capturedSystemPrompt = systemPrompt
        capturedUserMessage = userMessage
        modelReply("```json\n$VALID\n```")
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
    assertThat(result).isEqualTo(ActionFlow("com.example.app", listOf(Interaction.LaunchApp())))
  }

  @Test fun `truncated otherwise valid navigator reply is a model failure`() = runTest {
    val navigator = NavigatorAgent("context", executeRequest = { _, _ -> inspectionReply("appId: com.example\n---\n- launchApp", "length") })
    val failure = runCatching { navigator.generate(listOf("launch"), "com.example", Platform.IOS) }.exceptionOrNull()
    assertThat(failure is ModelFailureException).isEqualTo(true)
  }

  @Test fun `both navigator requests classify backend empty truncated and invalid responses safely`() = runTest {
    for (scroll in listOf(false, true)) {
      val stage = if (scroll) ModelRequestStage.NAVIGATOR_SCROLL else ModelRequestStage.NAVIGATOR_FLOW
      val valid = if (scroll) "DOWN" else VALID
      val cases = listOf(
        ModelFailureKind.REQUEST to suspend { throw IllegalStateException(SENTINEL) },
        ModelFailureKind.TIMEOUT to suspend {
          delay(30_001)
          modelReply(valid)
        },
        ModelFailureKind.EMPTY to suspend { modelReply(" \n") },
        ModelFailureKind.INVALID_RESPONSE to suspend { modelReply(if (scroll) "DOWN because the target is below" else "appId: com.example\n---\n- tapOn:") },
      ) + listOf("length", "LENGTH", "max_tokens", "MAX_TOKENS", "incomplete", "INCOMPLETE").map { reason -> ModelFailureKind.TRUNCATED to suspend { modelReply(valid, reason) } }
      for ((kind, response) in cases) {
        val navigator = NavigatorAgent("context") { _, _ -> response() }
        val failure = runCatching { if (scroll) navigator.suggestScrollDirection("target", "tree") else navigator.generate(listOf("navigate"), "com.example", Platform.IOS) }.exceptionOrNull() as ModelFailureException
        assertThat(failure.stage).isEqualTo(stage)
        assertThat(failure.failure).isEqualTo(kind)
        assertThat(failure.cause).isNull()
        assertThat(failure.suppressed).isEmpty()
        assertThat(failure.toString()).doesNotContain(SENTINEL)
      }
    }
  }

  @Test fun `strict directions normalize case and only exact NONE is an ordinary negative result`() = runTest {
    for (direction in Direction.entries) {
      assertThat(NavigatorAgent("context") { _, _ -> modelReply(" ${direction.name.lowercase()} \n") }.suggestScrollDirection("target", "tree")).isEqualTo(direction)
    }
    assertThat(NavigatorAgent("context") { _, _ -> modelReply(" none ") }.suggestScrollDirection("target", "tree")).isNull()
    for (reply in listOf("UNKNOWN", "NONE no scrolling needed", "DOWN\nexplanation")) {
      val error = runCatching { NavigatorAgent("context") { _, _ -> modelReply(reply) }.suggestScrollDirection("target", "tree") }.exceptionOrNull() as ModelFailureException
      assertThat(error.failure).isEqualTo(ModelFailureKind.INVALID_RESPONSE)
    }
  }

  @Test fun `truncation metadata rejects before structured decoding or validation`() = runTest {
    var validations = 0
    val navigator = NavigatorAgent("context", validateFlow = { validations++ }) { _, _ -> modelReply("invalid secret response", "length") }
    val failure = runCatching { navigator.generate(listOf("navigate"), "com.example", Platform.IOS) }.exceptionOrNull() as ModelFailureException
    assertThat(failure.failure).isEqualTo(ModelFailureKind.TRUNCATED)
    assertThat(validations).isEqualTo(0)
    assertThat(failure.cause).isNull()
  }

  @Test fun `local preparation failures remain outside backend classification`() = runTest {
    for (phase in ActionFlowPreparationPhase.entries) {
      val infrastructure = ActionFlowPreparationException(phase)
      val navigator = NavigatorAgent("context", validateFlow = { throw infrastructure }) { _, _ -> modelReply(VALID) }
      assertThat(runCatching { navigator.generate(listOf("navigate"), "com.example", Platform.IOS) }.exceptionOrNull()).isSameInstanceAs(infrastructure)
    }
  }

  @Test fun `unsupported generated actions are safe invalid model responses`() = runTest {
    for (reply in listOf("""{"actions":[{"type":"keyPress","keyName":"not-a-key"}]}""", """{"actions":[{"type":"tapOnText","text":"["}]}""", """{"appId":"other","actions":[]}""")) {
      val navigator = NavigatorAgent("context") { _, _ -> modelReply(reply) }
      val failure = runCatching { navigator.generate(listOf("navigate"), "com.example", Platform.IOS) }.exceptionOrNull() as ModelFailureException
      assertThat(failure.failure).isEqualTo(ModelFailureKind.INVALID_RESPONSE)
      assertThat(failure.cause).isNull()
    }
  }

  @Test fun `both navigator requests preserve explicit caller and enclosing timeout cancellation identity`() = runTest {
    for (scroll in listOf(false, true)) {
      val caller = CallerCancellation()
      val cancelled = NavigatorAgent("context") { _, _ -> throw caller }
      assertThat(runCatching { if (scroll) cancelled.suggestScrollDirection("target", "tree") else cancelled.generate(listOf("navigate"), "com.example", Platform.IOS) }.exceptionOrNull()).isSameInstanceAs(caller)
      val slow = NavigatorAgent("context") { _, _ ->
        delay(30_001)
        modelReply(VALID)
      }
      var observed: Throwable? = null
      val timeout = runCatching {
        withTimeout(100) {
          try {
            if (scroll) slow.suggestScrollDirection("target", "tree") else slow.generate(listOf("navigate"), "com.example", Platform.IOS)
          } catch (error: CancellationException) {
            observed = error
            throw error
          }
        }
      }.exceptionOrNull()
      assertThat(timeout is TimeoutCancellationException).isEqualTo(true)
      // Debug stack recovery may wrap timeout objects, but the original timeout identity remains in its cause chain.
      assertThat(generateSequence(timeout) { it.cause }.last()).isSameInstanceAs(generateSequence(observed) { it.cause }.last())
    }
  }

  private class CallerCancellation : CancellationException("caller") {
    val marker = Any()
  }
  companion object {
    const val VALID = """{"actions":[{"type":"launchApp"}]}"""
    const val SENTINEL = "HTTP-RAW-BODY header Bearer danger sk-secret eyJheader.payload.signature"
  }
}
