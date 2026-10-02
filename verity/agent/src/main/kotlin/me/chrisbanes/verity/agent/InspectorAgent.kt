package me.chrisbanes.verity.agent

import ai.koog.prompt.message.Message
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.chrisbanes.verity.core.model.InspectionVerdict

/** One request per check, retaining provider completion metadata until verdict validation. */
class InspectorAgent(
  private val evaluateTreeContent: suspend (systemPrompt: String, userMessage: String, references: List<Path>) -> Message.Assistant,
  private val evaluateVisualContent: suspend (systemPrompt: String, userMessage: String, screenshotPath: Path, references: List<Path>) -> Message.Assistant,
) {
  suspend fun evaluateTree(hierarchy: String, assertion: String, context: InspectionContext = InspectionContext()): InspectionVerdict = request(ModelRequestStage.INSPECTOR_TREE) {
    evaluateTreeContent(SYSTEM_PROMPT, withReferences(buildTreeMessage(hierarchy, assertion), context), context.referenceScreenshots)
  }

  suspend fun evaluateVisual(screenshotPath: Path, assertion: String, context: InspectionContext = InspectionContext()): InspectionVerdict = request(ModelRequestStage.INSPECTOR_VISUAL) {
    evaluateVisualContent(SYSTEM_PROMPT, withReferences(buildVisualMessage(assertion), context), screenshotPath, context.referenceScreenshots)
  }

  private suspend fun request(stage: ModelRequestStage, execute: suspend () -> Message.Assistant): InspectionVerdict {
    val response = try {
      withTimeoutOrNull(REQUEST_TIMEOUT) { execute() }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      throw ModelFailureException(stage, ModelFailureKind.REQUEST)
    } ?: throw ModelFailureException(stage, ModelFailureKind.TIMEOUT)
    if (response.finishReason?.lowercase() in setOf("length", "max_tokens", "incomplete")) {
      throw ModelFailureException(stage, ModelFailureKind.TRUNCATED)
    }
    return parseVerdict(response.textContent(), stage)
  }

  private fun withReferences(message: String, context: InspectionContext): String = buildString {
    append(message)
    if (context.referenceText.isNotEmpty() || context.referenceScreenshots.isNotEmpty()) {
      appendLine()
      appendLine("Reference context (earlier observations, not proof of the current condition):")
      if (context.referenceText.isNotEmpty()) appendLine(context.referenceText)
      context.referenceScreenshots.forEachIndexed { index, _ -> appendLine("Reference screenshot ${index + 1}") }
    }
  }.trim()

  companion object {
    private val strictJson = Json
    private val REQUEST_TIMEOUT = 30.seconds

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

    fun buildVisualMessage(assertion: String): String = "Current screenshot: evaluate the attached current screenshot against this assertion: $assertion"

    fun parseVerdict(response: String, stage: ModelRequestStage = ModelRequestStage.INSPECTOR_TREE): InspectionVerdict {
      if (response.isBlank()) throw ModelFailureException(stage, ModelFailureKind.EMPTY)
      val objectValue = try {
        strictJson.parseToJsonElement(response.stripCodeFences()) as? JsonObject
      } catch (_: Exception) {
        null
      } ?: throw ModelFailureException(stage, ModelFailureKind.INVALID_VERDICT)
      val passed = objectValue["passed"] as? JsonPrimitive
      val reasoning = objectValue["reasoning"] as? JsonPrimitive
      if (passed == null || passed.isString || passed.booleanOrNull == null || reasoning == null || !reasoning.isString) {
        throw ModelFailureException(stage, ModelFailureKind.INVALID_VERDICT)
      }
      return InspectionVerdict(passed.booleanOrNull!!, reasoning.content)
    }
  }
}
