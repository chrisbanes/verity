package me.chrisbanes.verity.agent

/**
 * LLM model configuration.
 *
 * NAVIGATOR models (cheap/structured — used for Maestro YAML generation):
 * - Anthropic: "claude-haiku-4-5" (recommended)
 * - Google: "gemini-3.0-flash"
 * - OpenAI: "gpt-5-mini"
 *
 * INSPECTOR models (capable/vision — used for assertion evaluation):
 * - Anthropic: "claude-sonnet-4-5" (recommended for Koog 0.6.4)
 * - Google: "gemini-3.1-pro"
 * - OpenAI: "gpt-5.4"
 *
 * When wiring Koog, replace these string constants with typed [LLModel] objects
 * (e.g., `AnthropicModels.Haiku_4_5`, `AnthropicModels.Sonnet_4_5`).
 */
object Models {
  const val NAVIGATOR = "claude-haiku-4-5"
  const val INSPECTOR = "claude-sonnet-4-5"
}
