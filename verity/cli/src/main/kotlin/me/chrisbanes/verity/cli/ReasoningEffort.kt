package me.chrisbanes.verity.cli

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val OPENAI_CHAT_BACKEND = "openai-api-key-chat-completions"
internal const val OPENAI_RESPONSES_BACKEND = "openai-api-key-responses"
internal const val ANTHROPIC_MESSAGES_BACKEND = "anthropic-api-key-messages"

internal class UnsupportedReasoningEffortException(
  val provider: String,
  val model: String,
  val backendId: String?,
  val requested: String,
) : IllegalArgumentException(
  "Reasoning effort '$requested' is unsupported for provider '$provider', model '$model'" +
    (backendId?.let { " on backend '$it'" } ?: " on an unvalidated backend"),
)

internal fun currentReasoningBackendId(provider: VerityProvider, model: LLModel): String? = when (provider) {
  VerityProvider.OpenAI -> when {
    model.supports(LLMCapability.OpenAIEndpoint.Completions) -> OPENAI_CHAT_BACKEND
    model.supports(LLMCapability.OpenAIEndpoint.Responses) -> OPENAI_RESPONSES_BACKEND
    else -> null
  }

  VerityProvider.Anthropic -> ANTHROPIC_MESSAGES_BACKEND

  else -> null
}

internal fun resolveReasoningEffort(
  provider: VerityProvider,
  model: LLModel,
  backendId: String?,
  requested: String?,
): LLMParams {
  if (requested == null) return LLMParams()

  val values = supportedEfforts[EffortCapability(provider.name, model.id, backendId)]
  if (requested !in values.orEmpty()) {
    throw UnsupportedReasoningEffortException(provider.name, model.id, backendId, requested)
  }

  val properties = when (backendId) {
    OPENAI_CHAT_BACKEND -> mapOf("reasoning_effort" to JsonPrimitive(requested))

    OPENAI_RESPONSES_BACKEND -> mapOf(
      "reasoning" to buildJsonObject { put("effort", JsonPrimitive(requested)) },
    )

    ANTHROPIC_MESSAGES_BACKEND -> mapOf(
      "output_config" to buildJsonObject { put("effort", JsonPrimitive(requested)) },
    )

    else -> error("A validated reasoning effort must have a supported backend")
  }
  return LLMParams(additionalProperties = properties)
}

private data class EffortCapability(
  val provider: String,
  val model: String,
  val backendId: String?,
)

private val supportedEfforts = buildMap {
  fun row(provider: String, model: String, backendId: String, vararg efforts: String) {
    put(EffortCapability(provider, model, backendId), efforts.toSet())
  }

  row("openai", "gpt-5", OPENAI_CHAT_BACKEND, "minimal", "low", "medium", "high")
  row("openai", "gpt-5", OPENAI_RESPONSES_BACKEND, "minimal", "low", "medium", "high")
  row("openai", "gpt-5.1", OPENAI_CHAT_BACKEND, "none", "low", "medium", "high")
  row("openai", "gpt-5.1", OPENAI_RESPONSES_BACKEND, "none", "low", "medium", "high")
  row("openai", "gpt-5.2", OPENAI_CHAT_BACKEND, "none", "low", "medium", "high", "xhigh")
  row("openai", "gpt-5.2", OPENAI_RESPONSES_BACKEND, "none", "low", "medium", "high", "xhigh")
  row("openai", "gpt-5-pro", OPENAI_RESPONSES_BACKEND, "high")
  row("openai", "gpt-5.2-pro", OPENAI_RESPONSES_BACKEND, "medium", "high", "xhigh")
  row("anthropic", "claude-opus-4-5", ANTHROPIC_MESSAGES_BACKEND, "low", "medium", "high")
  row("anthropic", "claude-opus-4-6", ANTHROPIC_MESSAGES_BACKEND, "low", "medium", "high", "max")
  row("anthropic", "claude-opus-4-7", ANTHROPIC_MESSAGES_BACKEND, "low", "medium", "high", "xhigh", "max")
  row("anthropic", "claude-sonnet-4-6", ANTHROPIC_MESSAGES_BACKEND, "low", "medium", "high", "max")
}
