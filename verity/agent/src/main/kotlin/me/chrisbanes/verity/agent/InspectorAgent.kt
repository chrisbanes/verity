package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.model.InspectionVerdict
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * Evaluates assertions against screen state.
 * Uses a capable model (Sonnet-class) for accuracy.
 * Supports both tree-based (text) and visual (screenshot) evaluation.
 *
 * Tree evaluation uses Koog's `AIAgent.run(message)` (text-only).
 * Visual evaluation uses Koog's prompt DSL with image attachments via
 * `PromptExecutor.execute()` since `AIAgent.run` does not support multimodal input.
 */
class InspectorAgent {

  /**
   * Evaluate an assertion against the accessibility tree text.
   */
  suspend fun evaluateTree(hierarchy: String, assertion: String): InspectionVerdict {
    val message = buildTreeMessage(hierarchy, assertion)
    // Koog wiring (production-ready milestone):
    //   val agent = AIAgent(
    //     promptExecutor = simpleAnthropicExecutor(apiKey),
    //     systemPrompt = SYSTEM_PROMPT,
    //     llmModel = AnthropicModels.Sonnet_4_5,
    //   )
    //   val response = agent.run(message)
    //   return parseVerdict(response)
    TODO("Wire Koog AIAgent call for tree evaluation — see Models.INSPECTOR")
  }

  /**
   * Evaluate an assertion against a screenshot.
   */
  suspend fun evaluateVisual(screenshotPath: Path, assertion: String): InspectionVerdict {
    val message = buildVisualMessage(assertion)
    // Koog wiring (production-ready milestone) — uses prompt DSL for image attachment:
    //   val prompt = prompt("visual-eval") {
    //     system(SYSTEM_PROMPT)
    //     user {
    //       text(message)
    //       attachments { image(kotlinx.io.files.Path(screenshotPath.toString())) }
    //     }
    //   }
    //   val response = executor.execute(prompt, model, emptyList()).first().content
    //   return parseVerdict(response)
    TODO("Wire Koog prompt DSL with vision for visual evaluation — see Models.INSPECTOR")
  }

  companion object {
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
    private val CODE_FENCE = Regex("```\\w*\\n?|```")

    const val SYSTEM_PROMPT =
      "You are a visual testing inspector for a mobile/TV app.\n" +
        "Evaluate whether a screenshot or accessibility tree matches an assertion.\n" +
        "Respond with ONLY JSON: {\"passed\": true/false, \"reasoning\": \"...\"}\n" +
        "Do not include any other text or explanation outside the JSON."

    fun buildTreeMessage(hierarchy: String, assertion: String): String = buildString {
      appendLine("Accessibility tree:")
      appendLine(hierarchy)
      appendLine()
      appendLine("Assertion to evaluate: $assertion")
    }.trim()

    fun buildVisualMessage(assertion: String): String =
      "Evaluate the attached screenshot against this assertion: $assertion"

    fun parseVerdict(response: String): InspectionVerdict {
      val cleaned = response.replace(CODE_FENCE, "").trim()
      return try {
        lenientJson.decodeFromString(InspectionVerdict.serializer(), cleaned)
      } catch (e: Exception) {
        InspectionVerdict(
          passed = false,
          reasoning = "Inspector parse error: ${e.message}. Raw response: $cleaned",
        )
      }
    }
  }
}
