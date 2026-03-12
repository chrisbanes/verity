package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.model.Platform

/**
 * Generates Maestro YAML flows from natural language action steps.
 * Uses a cheap model (Haiku-class) since the task is structured generation.
 *
 * The [generate] method requires a Koog AIAgent. For the scaffold milestone,
 * prompt construction and response cleaning are fully implemented and tested;
 * the LLM call site is a TODO.
 */
class NavigatorAgent(
  private val bundledContext: String,
) {

  /**
   * Generate Maestro YAML for the given actions.
   *
   * @param actions Natural language action instructions
   * @param appId The app package/bundle ID
   * @param platform Target platform
   * @param injectedContext Optional app-specific context from --context-path or MCP get_context
   * @return Generated Maestro YAML string
   */
  suspend fun generate(
    actions: List<String>,
    appId: String,
    platform: Platform,
    injectedContext: String = "",
  ): String {
    val systemPrompt = buildSystemPrompt(platform, bundledContext, injectedContext)
    val userMessage = buildUserMessage(actions, appId)

    // Koog wiring (production-ready milestone):
    //   val agent = AIAgent(
    //     promptExecutor = simpleAnthropicExecutor(apiKey),
    //     systemPrompt = systemPrompt,
    //     llmModel = AnthropicModels.Haiku_4_5,
    //   )
    //   val response = agent.run(userMessage)
    //   return cleanResponse(response)
    TODO("Wire Koog AIAgent call — see Models.NAVIGATOR")
  }

  companion object {
    private val CODE_FENCE = Regex("```\\w*\\n?|```")

    fun buildSystemPrompt(
      platform: Platform,
      bundledContext: String,
      injectedContext: String,
    ): String {
      val platformInstructions = when (platform) {
        Platform.ANDROID_TV -> """
          You are generating Maestro YAML for an Android TV app.
          Android TV uses D-pad navigation (Remote Dpad Up/Down/Left/Right/Center).
          Always add waitForAnimationToEnd after navigation actions.
          Use extendedWaitUntil for content that needs time to load.
        """.trimIndent()

        Platform.ANDROID_MOBILE -> """
          You are generating Maestro YAML for an Android mobile app.
          Use tap, swipe, scroll, and input commands.
          Always add waitForAnimationToEnd after navigation actions.
        """.trimIndent()

        Platform.IOS -> """
          You are generating Maestro YAML for an iOS app.
          Use tap, swipe, scroll, and input commands.
          Always add waitForAnimationToEnd after navigation actions.
        """.trimIndent()
      }

      return buildString {
        appendLine("Generate ONLY valid Maestro YAML. No explanation, no markdown code blocks, just raw YAML.")
        appendLine("Start with `appId: <id>`, then `---`, then commands.")
        appendLine("Do NOT include screenshots or assertions.")
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
      }.trim()
    }

    fun buildUserMessage(actions: List<String>, appId: String): String = buildString {
      appendLine("App ID: $appId")
      appendLine()
      appendLine("Generate a Maestro YAML flow for these actions:")
      actions.forEachIndexed { index, action ->
        appendLine("${index + 1}. $action")
      }
    }.trim()

    fun cleanResponse(response: String): String = response.replace(CODE_FENCE, "").trim()
  }
}
