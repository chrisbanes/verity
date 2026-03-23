package me.chrisbanes.verity.agent

import ai.koog.agents.core.agent.AIAgent
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.chrisbanes.verity.core.model.InspectionVerdict

/**
 * Evaluates assertions against screen state.
 * Uses a capable model (Sonnet-class) for accuracy.
 * Supports both tree-based (text) and visual (screenshot) evaluation.
 *
 * Tree evaluation uses Koog's `AIAgent.run(message)` (text-only).
 * Visual evaluation uses Koog's prompt DSL with image attachments via
 * `PromptExecutor.execute()` since `AIAgent.run` does not support multimodal input.
 */
class InspectorAgent(
  private val treeAgentFactory: () -> AIAgent<String, String>,
  private val evaluateVisualContent: suspend (systemPrompt: String, userMessage: String, screenshotPath: Path) -> String,
) {

  /**
   * Evaluate an assertion against the accessibility tree text.
   */
  suspend fun evaluateTree(hierarchy: String, assertion: String): InspectionVerdict {
    val message = buildTreeMessage(hierarchy, assertion)
    val agent = treeAgentFactory()
    return try {
      val response = withTimeout(TREE_TIMEOUT) {
        agent.run(message)
      }
      parseVerdict(response)
    } finally {
      withContext(NonCancellable) { agent.close() }
    }
  }

  /**
   * Evaluate an assertion against a screenshot.
   */
  suspend fun evaluateVisual(screenshotPath: Path, assertion: String): InspectionVerdict {
    val message = buildVisualMessage(assertion)
    val response = evaluateVisualContent(SYSTEM_PROMPT, message, screenshotPath)
    return parseVerdict(response)
  }

  companion object {
    private val lenientJson = Json {
      ignoreUnknownKeys = true
      isLenient = true
    }

    /** Timeout for tree-based evaluation (text-only, should be fast). */
    private val TREE_TIMEOUT = 30.seconds

    /** Maximum length of raw response included in error messages to prevent oversized output. */
    private const val MAX_RAW_RESPONSE_LENGTH = 500

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

    fun buildVisualMessage(assertion: String): String = "Evaluate the attached screenshot against this assertion: $assertion"

    fun parseVerdict(response: String): InspectionVerdict {
      val cleaned = response.stripCodeFences()
      return try {
        lenientJson.decodeFromString(InspectionVerdict.serializer(), cleaned)
      } catch (e: Exception) {
        val truncated = if (cleaned.length > MAX_RAW_RESPONSE_LENGTH) {
          cleaned.take(MAX_RAW_RESPONSE_LENGTH) + "... [truncated ${cleaned.length - MAX_RAW_RESPONSE_LENGTH} chars]"
        } else {
          cleaned
        }
        InspectionVerdict(
          passed = false,
          reasoning = "Inspector parse error: ${e.message}. Raw response: $truncated",
        )
      }
    }
  }
}
