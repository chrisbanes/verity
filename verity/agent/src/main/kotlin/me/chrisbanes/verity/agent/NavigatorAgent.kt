package me.chrisbanes.verity.agent

import ai.koog.prompt.message.Message
import kotlin.coroutines.cancellation.CancellationException
import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.InvalidActionFlowException
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.validateActionFlow

/** Generates device-free validated structured actions, retaining completion metadata per request. */
class NavigatorAgent(
  private val bundledContext: String,
  private val validateFlow: suspend (ActionFlow) -> Unit = { validateActionFlow(it) },
  private val executeRequest: suspend (systemPrompt: String, userMessage: String) -> Message.Assistant,
) {

  /**
   * Generate structured actions for the given instructions.
   *
   * @param actions Natural language action instructions
   * @param appId The app package/bundle ID
   * @param platform Target platform
   * @param injectedContext Optional app-specific context from --context-path or MCP get_context
   * @return The validated complete action list with the requested application context
   */
  suspend fun generate(
    actions: List<String>,
    appId: String,
    platform: Platform,
    injectedContext: String = "",
  ): ActionFlow {
    val systemPrompt = buildSystemPrompt(platform, bundledContext, injectedContext)
    val userMessage = buildUserMessage(actions, appId)
    val response = cleanResponse(requestModelText(ModelRequestStage.NAVIGATOR_FLOW) { executeRequest(systemPrompt, userMessage) })
    return try {
      ActionFlow.decodeResponse(appId, response).also { validateFlow(it) }
    } catch (error: CancellationException) {
      throw error
    } catch (_: InvalidActionFlowException) {
      throw ModelFailureException(ModelRequestStage.NAVIGATOR_FLOW, ModelFailureKind.INVALID_RESPONSE)
    }
  }

  /**
   * Ask the LLM which direction to scroll to find a target element.
   *
   * @param target The text or ID of the element to find
   * @param hierarchy The current accessibility tree as rendered text
   * @return The suggested scroll direction, or null if the LLM believes scrolling won't help
   */
  suspend fun suggestScrollDirection(target: String, hierarchy: String): Direction? {
    val systemPrompt =
      "You are helping find a UI element that isn't currently visible on screen. " +
        "Given the current accessibility tree and a target element, suggest which direction to scroll " +
        "(UP, DOWN, LEFT, or RIGHT) to find it. Respond with ONLY the direction word, or NONE if you believe " +
        "the element cannot be found by scrolling."
    val userMessage = "Target: $target\n\nCurrent screen:\n$hierarchy"
    val response = requestModelText(ModelRequestStage.NAVIGATOR_SCROLL) { executeRequest(systemPrompt, userMessage) }.trim().uppercase()
    if (response == "NONE") return null
    return Direction.entries.firstOrNull { it.name == response }
      ?: throw ModelFailureException(ModelRequestStage.NAVIGATOR_SCROLL, ModelFailureKind.INVALID_RESPONSE)
  }

  companion object {
    fun buildSystemPrompt(
      platform: Platform,
      bundledContext: String,
      injectedContext: String,
    ): String {
      val platformInstructions = when (platform) {
        Platform.ANDROID_TV -> """
          You are generating structured actions for an Android TV app.
          Android TV uses D-pad navigation (Remote Dpad Up/Down/Left/Right/Center).
          Always add waitForAnimation after navigation actions.
          Use waitUntilVisible for content that needs time to load.
        """.trimIndent()

        Platform.ANDROID_MOBILE -> """
          You are generating structured actions for an Android mobile app.
          Use tap, swipe, scroll, and input commands.
          Always add waitForAnimation after navigation actions.
        """.trimIndent()

        Platform.IOS -> """
          You are generating structured actions for an iOS app.
          Use tap, swipe, scroll, and input commands.
          Always add waitForAnimation after navigation actions.
        """.trimIndent()
      }

      return buildString {
        appendLine("Generate ONLY a strict JSON object with one property, actions, containing an ordered array.")
        appendLine("Do not include appId, YAML, explanations, or Markdown code fences.")
        appendLine("Do NOT include screenshots or assertions.")
        appendLine("Each action has type and only the fields specified here:")
        appendLine("keyPress(keyName); tapOnText(text); tapOnId(resourceId); longPressOnText(text); inputText(text).")
        appendLine("scroll(direction); swipe(direction): direction is UP, DOWN, LEFT, or RIGHT.")
        appendLine("longPressOnFocused; pullToRefresh; defaultScroll: no additional fields.")
        appendLine("launchApp(clearState optional boolean); waitForAnimation(timeoutMs optional positive integer).")
        appendLine("waitUntilVisible(timeoutMs positive integer, exactly one of text or resourceId).")
        appendLine("Text and resourceId selectors are regular expressions; escape literal metacharacters.")
        appendLine("Example: {\"actions\":[{\"type\":\"tapOnText\",\"text\":\"Settings\"},{\"type\":\"waitForAnimation\"}]}")
        appendLine()
        appendLine("Bundled context (always present):")
        appendLine(bundledContext)
        appendLine()
        appendLine(platformInstructions)
        if (injectedContext.isNotBlank()) {
          appendLine()
          appendLine("Injected app-specific context (optional):")
          appendLine(injectedContext)
        }
        appendLine()
        appendLine("Context examples describe the app; always return the structured JSON schema above, even when examples use YAML.")
      }.trim()
    }

    fun buildUserMessage(actions: List<String>, appId: String): String = buildString {
      appendLine("App ID: $appId")
      appendLine()
      appendLine("Generate the structured JSON action list for these instructions:")
      actions.forEachIndexed { index, action ->
        appendLine("${index + 1}. $action")
      }
    }.trim()

    fun cleanResponse(response: String): String = response.stripCodeFences()
  }
}
