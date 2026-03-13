package me.chrisbanes.verity.cli

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.clients.dashscope.DashscopeLLMClient
import ai.koog.prompt.executor.clients.dashscope.DashscopeModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.prompt.llm.LLModel
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider

/**
 * Represents a supported LLM provider for Verity.
 *
 * Each subclass specifies the provider's name, default models, authentication requirements,
 * and how to create the underlying Koog LLM client.
 */
sealed class VerityProvider {
  /** CLI/config identifier for this provider. */
  abstract val name: String

  /** Cheap/fast model for YAML generation (navigator). */
  abstract val defaultNavigatorModel: LLModel

  /** Most capable model for assertion evaluation (inspector). */
  abstract val defaultInspectorModel: LLModel

  /** Environment variable name for the API key. */
  abstract val envVar: String

  /** Whether an API key is required to use this provider. False only for Ollama. */
  abstract val requiresAuth: Boolean

  /** All known chat models for this provider. */
  abstract fun allModels(): List<LLModel>

  /** Creates the Koog LLM client for this provider using the given API key (or host URL for Ollama). */
  abstract fun createClient(apiKey: String): LLMClient

  /** Looks up a model by its ID string, throws [IllegalStateException] if not found. */
  fun findModel(id: String): LLModel {
    val models = allModels()
    return models.find { it.id == id }
      ?: error("Unknown model '$id' for provider '$name'. Available: ${models.map { it.id }}")
  }

  object Anthropic : VerityProvider() {
    override val name = "anthropic"
    override val defaultNavigatorModel: LLModel = AnthropicModels.Haiku_4_5
    override val defaultInspectorModel: LLModel = AnthropicModels.Opus_4_5
    override val envVar = "ANTHROPIC_API_KEY"
    override val requiresAuth = true

    override fun allModels(): List<LLModel> = listOf(
      AnthropicModels.Opus_3,
      AnthropicModels.Haiku_3,
      AnthropicModels.Sonnet_3_5,
      AnthropicModels.Haiku_3_5,
      AnthropicModels.Sonnet_3_7,
      AnthropicModels.Sonnet_4,
      AnthropicModels.Opus_4,
      AnthropicModels.Opus_4_1,
      AnthropicModels.Opus_4_5,
      AnthropicModels.Sonnet_4_5,
      AnthropicModels.Haiku_4_5,
    )

    override fun createClient(apiKey: String): AnthropicLLMClient = AnthropicLLMClient(apiKey)
  }

  object OpenAI : VerityProvider() {
    override val name = "openai"
    override val defaultNavigatorModel: LLModel = OpenAIModels.Chat.GPT4oMini
    override val defaultInspectorModel: LLModel = OpenAIModels.Chat.GPT4o
    override val envVar = "OPENAI_API_KEY"
    override val requiresAuth = true

    override fun allModels(): List<LLModel> = listOf(
      OpenAIModels.Chat.GPT4o,
      OpenAIModels.Chat.GPT4oMini,
      OpenAIModels.Chat.GPT4_1,
      OpenAIModels.Chat.GPT4_1Nano,
      OpenAIModels.Chat.GPT4_1Mini,
      OpenAIModels.Chat.O1,
      OpenAIModels.Chat.O3,
      OpenAIModels.Chat.O3Mini,
      OpenAIModels.Chat.O4Mini,
      OpenAIModels.Chat.GPT5,
      OpenAIModels.Chat.GPT5Mini,
      OpenAIModels.Chat.GPT5Nano,
      OpenAIModels.Chat.GPT5Codex,
      OpenAIModels.Chat.GPT5Pro,
      OpenAIModels.Chat.GPT5_1,
      OpenAIModels.Chat.GPT5_1Codex,
      OpenAIModels.Chat.GPT5_2,
      OpenAIModels.Chat.GPT5_2Pro,
    )

    override fun createClient(apiKey: String): OpenAILLMClient = OpenAILLMClient(apiKey)
  }

  object Google : VerityProvider() {
    override val name = "google"
    override val defaultNavigatorModel: LLModel = GoogleModels.Gemini2_0FlashLite
    override val defaultInspectorModel: LLModel = GoogleModels.Gemini2_5Pro
    override val envVar = "GOOGLE_API_KEY"
    override val requiresAuth = true

    override fun allModels(): List<LLModel> = listOf(
      GoogleModels.Gemini2_0Flash,
      GoogleModels.Gemini2_0Flash001,
      GoogleModels.Gemini2_0FlashLite,
      GoogleModels.Gemini2_0FlashLite001,
      GoogleModels.Gemini2_5Pro,
      GoogleModels.Gemini2_5Flash,
      GoogleModels.Gemini2_5FlashLite,
      GoogleModels.Gemini3_Pro_Preview,
    )

    override fun createClient(apiKey: String): GoogleLLMClient = GoogleLLMClient(apiKey)
  }

  object OpenRouter : VerityProvider() {
    override val name = "openrouter"
    override val defaultNavigatorModel: LLModel = OpenRouterModels.Phi4Reasoning
    override val defaultInspectorModel: LLModel = OpenRouterModels.Claude4_5Opus
    override val envVar = "OPENROUTER_API_KEY"
    override val requiresAuth = true

    override fun allModels(): List<LLModel> = listOf(
      OpenRouterModels.Phi4Reasoning,
      OpenRouterModels.Claude3Opus,
      OpenRouterModels.Claude3Sonnet,
      OpenRouterModels.Claude3Haiku,
      OpenRouterModels.Claude3_5Sonnet,
      OpenRouterModels.Claude3_7Sonnet,
      OpenRouterModels.Claude4Sonnet,
      OpenRouterModels.Claude4_1Opus,
      OpenRouterModels.Claude4_5Haiku,
      OpenRouterModels.Claude4_5Sonnet,
      OpenRouterModels.Claude4_5Opus,
      OpenRouterModels.Claude3VisionSonnet,
      OpenRouterModels.Claude3VisionOpus,
      OpenRouterModels.Claude3VisionHaiku,
      OpenRouterModels.GPT35Turbo,
      OpenRouterModels.GPT4,
      OpenRouterModels.GPT4o,
      OpenRouterModels.GPT4oMini,
      OpenRouterModels.GPT4Turbo,
      OpenRouterModels.GPT5,
      OpenRouterModels.GPT5Mini,
      OpenRouterModels.GPT5Nano,
      OpenRouterModels.GPT5Chat,
      OpenRouterModels.GPT_OSS_120b,
      OpenRouterModels.GPT5_2,
      OpenRouterModels.GPT5_2Pro,
      OpenRouterModels.Llama3,
      OpenRouterModels.Llama3Instruct,
      OpenRouterModels.Mistral7B,
      OpenRouterModels.Mixtral8x7B,
      OpenRouterModels.DeepSeekV30324,
      OpenRouterModels.Gemini2_5FlashLite,
      OpenRouterModels.Gemini2_5Flash,
      OpenRouterModels.Gemini2_5Pro,
      OpenRouterModels.Qwen2_5,
      OpenRouterModels.Qwen3VL,
    )

    override fun createClient(apiKey: String): OpenRouterLLMClient = OpenRouterLLMClient(apiKey)
  }

  object Bedrock : VerityProvider() {
    override val name = "bedrock"
    override val defaultNavigatorModel: LLModel = BedrockModels.AnthropicClaude4_5Haiku
    override val defaultInspectorModel: LLModel = BedrockModels.AnthropicClaude4_5Sonnet
    override val envVar = "AWS_ACCESS_KEY_ID"
    override val requiresAuth = true

    override fun allModels(): List<LLModel> = listOf(
      BedrockModels.AnthropicClaude3Opus,
      BedrockModels.AnthropicClaude3Sonnet,
      BedrockModels.AnthropicClaude3Haiku,
      BedrockModels.AnthropicClaude4Opus,
      BedrockModels.AnthropicClaude41Opus,
      BedrockModels.AnthropicClaude45Opus,
      BedrockModels.AnthropicClaude4Sonnet,
      BedrockModels.AnthropicClaude4_5Sonnet,
      BedrockModels.AnthropicClaude4_5Haiku,
      BedrockModels.AnthropicClaude35SonnetV2,
      BedrockModels.AnthropicClaude35Haiku,
      BedrockModels.AnthropicClaude21,
      BedrockModels.AnthropicClaudeInstant,
      BedrockModels.AmazonNovaMicro,
      BedrockModels.AmazonNovaLite,
      BedrockModels.AmazonNovaPro,
      BedrockModels.AmazonNovaPremier,
      BedrockModels.AI21JambaLarge,
      BedrockModels.AI21JambaMini,
      BedrockModels.MetaLlama3_0_8BInstruct,
      BedrockModels.MetaLlama3_0_70BInstruct,
      BedrockModels.MetaLlama3_1_8BInstruct,
      BedrockModels.MetaLlama3_1_70BInstruct,
      BedrockModels.MetaLlama3_1_405BInstruct,
      BedrockModels.MetaLlama3_2_1BInstruct,
      BedrockModels.MetaLlama3_2_3BInstruct,
      BedrockModels.MetaLlama3_2_11BInstruct,
      BedrockModels.MetaLlama3_2_90BInstruct,
      BedrockModels.MetaLlama3_3_70BInstruct,
    )

    override fun createClient(apiKey: String): BedrockLLMClient {
      val secret = System.getenv("AWS_SECRET_ACCESS_KEY")
        ?: error("AWS_SECRET_ACCESS_KEY environment variable is required for the Bedrock provider")
      val credProvider =
        StaticCredentialsProvider {
          accessKeyId = apiKey
          secretAccessKey = secret
        }
      return BedrockLLMClient(identityProvider = credProvider)
    }
  }

  object DeepSeek : VerityProvider() {
    override val name = "deepseek"
    override val defaultNavigatorModel: LLModel = DeepSeekModels.DeepSeekChat
    override val defaultInspectorModel: LLModel = DeepSeekModels.DeepSeekReasoner
    override val envVar = "DEEPSEEK_API_KEY"
    override val requiresAuth = true

    override fun allModels(): List<LLModel> = listOf(
      DeepSeekModels.DeepSeekChat,
      DeepSeekModels.DeepSeekReasoner,
    )

    override fun createClient(apiKey: String): DeepSeekLLMClient = DeepSeekLLMClient(apiKey)
  }

  object MistralAI : VerityProvider() {
    override val name = "mistralai"
    override val defaultNavigatorModel: LLModel = MistralAIModels.Chat.MistralSmall2
    override val defaultInspectorModel: LLModel = MistralAIModels.Chat.MagistralMedium12
    override val envVar = "MISTRAL_API_KEY"
    override val requiresAuth = true

    override fun allModels(): List<LLModel> = listOf(
      MistralAIModels.Chat.MistralMedium31,
      MistralAIModels.Chat.MistralLarge21,
      MistralAIModels.Chat.MistralSmall2,
      MistralAIModels.Chat.MagistralMedium12,
      MistralAIModels.Chat.Codestral,
      MistralAIModels.Chat.DevstralMedium,
    )

    override fun createClient(apiKey: String): MistralAILLMClient = MistralAILLMClient(apiKey)
  }

  object Ollama : VerityProvider() {
    override val name = "ollama"
    override val defaultNavigatorModel: LLModel = OllamaModels.Meta.LLAMA_3_2_3B
    override val defaultInspectorModel: LLModel = OllamaModels.Meta.LLAMA_4_SCOUT
    override val envVar = "OLLAMA_HOST"
    override val requiresAuth = false

    override fun allModels(): List<LLModel> = listOf(
      OllamaModels.Groq.LLAMA_3_GROK_TOOL_USE_8B,
      OllamaModels.Groq.LLAMA_3_GROK_TOOL_USE_70B,
      OllamaModels.Meta.LLAMA_3_2_3B,
      OllamaModels.Meta.LLAMA_3_2,
      OllamaModels.Meta.LLAMA_4_SCOUT,
      OllamaModels.Meta.LLAMA_4,
      OllamaModels.Alibaba.QWEN_2_5_05B,
      OllamaModels.Alibaba.QWEN_3_06B,
      OllamaModels.Alibaba.QWQ_32B,
      OllamaModels.Alibaba.QWQ,
      OllamaModels.Alibaba.QWEN_CODER_2_5_32B,
      OllamaModels.Granite.GRANITE_3_2_VISION,
    )

    override fun createClient(apiKey: String): OllamaClient = OllamaClient(baseUrl = apiKey.ifBlank { "http://localhost:11434" })
  }

  object DashScope : VerityProvider() {
    override val name = "dashscope"
    override val defaultNavigatorModel: LLModel = DashscopeModels.QWEN_FLASH
    override val defaultInspectorModel: LLModel = DashscopeModels.QWEN3_MAX
    override val envVar = "DASHSCOPE_API_KEY"
    override val requiresAuth = true

    override fun allModels(): List<LLModel> = listOf(
      DashscopeModels.QWEN_FLASH,
      DashscopeModels.QWEN3_OMNI_FLASH,
      DashscopeModels.QWEN_PLUS,
      DashscopeModels.QWEN_PLUS_LATEST,
      DashscopeModels.QWEN3_CODER_PLUS,
      DashscopeModels.QWEN3_CODER_FLASH,
      DashscopeModels.QWEN3_MAX,
    )

    override fun createClient(apiKey: String): DashscopeLLMClient = DashscopeLLMClient(apiKey)
  }

  companion object {
    /** All 9 supported providers. */
    val all: List<VerityProvider> =
      listOf(
        Anthropic,
        OpenAI,
        Google,
        OpenRouter,
        Bedrock,
        DeepSeek,
        MistralAI,
        Ollama,
        DashScope,
      )

    /** Looks up a provider by name, throws [IllegalStateException] for unknown names. */
    fun fromName(name: String): VerityProvider = all.find { it.name == name }
      ?: error(
        "Unknown provider '$name'. Available providers: ${all.map { it.name }}",
      )
  }
}
