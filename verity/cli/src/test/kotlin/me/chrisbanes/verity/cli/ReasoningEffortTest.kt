package me.chrisbanes.verity.cli

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.params.LLMParams
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class ReasoningEffortTest {
  @Test
  fun `accepts every exact capability row and preserves native parameter values`() {
    val capabilities = listOf(
      Capability("openai", "gpt-5", OPENAI_CHAT_BACKEND, setOf("minimal", "low", "medium", "high"), "reasoning_effort"),
      Capability("openai", "gpt-5.1", OPENAI_CHAT_BACKEND, setOf("none", "low", "medium", "high"), "reasoning_effort"),
      Capability("openai", "gpt-5.2", OPENAI_CHAT_BACKEND, setOf("none", "low", "medium", "high", "xhigh"), "reasoning_effort"),
      Capability("openai", "gpt-5-pro", OPENAI_RESPONSES_BACKEND, setOf("high"), "reasoning"),
      Capability("openai", "gpt-5.2-pro", OPENAI_RESPONSES_BACKEND, setOf("medium", "high", "xhigh"), "reasoning"),
      Capability("anthropic", "claude-opus-4-5", ANTHROPIC_MESSAGES_BACKEND, setOf("low", "medium", "high"), "output_config"),
      Capability("anthropic", "claude-opus-4-6", ANTHROPIC_MESSAGES_BACKEND, setOf("low", "medium", "high", "max"), "output_config"),
      Capability("anthropic", "claude-opus-4-7", ANTHROPIC_MESSAGES_BACKEND, setOf("low", "medium", "high", "xhigh", "max"), "output_config"),
      Capability("anthropic", "claude-sonnet-4-6", ANTHROPIC_MESSAGES_BACKEND, setOf("low", "medium", "high", "max"), "output_config"),
    )

    capabilities.forEach { capability ->
      val provider = provider(capability.provider)
      val model = provider.findModel(capability.model)
      capability.efforts.forEach { effort ->
        val params = resolveReasoningEffort(provider, model, capability.backendId, effort)
        assertThat(params.additionalProperties.orEmpty().keys).contains(capability.property)
        val value = params.additionalProperties.orEmpty().getValue(capability.property)
        if (capability.property == "reasoning_effort") {
          assertThat(value).isEqualTo(JsonPrimitive(effort))
        } else {
          assertThat(value.toString()).contains("\"effort\":\"$effort\"")
        }
      }
    }
  }

  @Test
  fun `rejects unsupported neighbors and unvalidated backend identities`() {
    assertUnsupported("openai", "gpt-5", OPENAI_CHAT_BACKEND, "none")
    assertUnsupported("openai", "gpt-5.1", OPENAI_CHAT_BACKEND, "minimal")
    assertUnsupported("openai", "gpt-5-pro", OPENAI_RESPONSES_BACKEND, "medium")
    assertUnsupported("openai", "gpt-5.2", "openai-chatgpt-codex", "xhigh")
    assertUnsupported("openai", "gpt-4o", OPENAI_CHAT_BACKEND, "high")
    assertUnsupported("openai", "gpt-5.1", OPENAI_CHAT_BACKEND, "HIGH")
    assertUnsupported("openai", "gpt-5.1", OPENAI_CHAT_BACKEND, " high ")
    assertUnsupported("openai", "gpt-5.1", OPENAI_CHAT_BACKEND, "")
    assertUnsupported("google", "gemini-2.5-pro", null, "high")
    assertUnsupported("anthropic", "claude-haiku-4-5", ANTHROPIC_MESSAGES_BACKEND, "high")
  }

  @Test
  fun `unset effort keeps empty backend defaults`() {
    listOf(
      VerityProvider.Anthropic,
      VerityProvider.OpenAI,
      VerityProvider.Google,
      VerityProvider.OpenRouter,
      VerityProvider.Bedrock,
      VerityProvider.DeepSeek,
      VerityProvider.MistralAI,
      VerityProvider.Ollama,
      VerityProvider.DashScope,
    ).forEach { provider ->
      val params = resolveReasoningEffort(provider, provider.defaultNavigatorModel, null, null)
      assertThat(params).isEqualTo(LLMParams())
      assertThat(params.additionalProperties.orEmpty()).isEmpty()
    }
  }

  @Test
  fun `selected OpenAI endpoint follows model capabilities`() {
    val provider = VerityProvider.OpenAI
    assertThat(currentReasoningBackendId(provider, provider.findModel("gpt-5.2"))).isEqualTo(OPENAI_CHAT_BACKEND)
    assertThat(currentReasoningBackendId(provider, provider.findModel("gpt-5.2-pro"))).isEqualTo(OPENAI_RESPONSES_BACKEND)
    assertThat(currentReasoningBackendId(VerityProvider.Anthropic, VerityProvider.Anthropic.findModel("claude-opus-4-6")))
      .isEqualTo(ANTHROPIC_MESSAGES_BACKEND)
  }

  @Test
  fun `Koog clients serialize native effort fields through an offline HTTP client`() = runTest {
    val cases = listOf(
      Triple("openai-chat", VerityProvider.OpenAI.findModel("gpt-5.2"), "xhigh"),
      Triple("openai-responses", VerityProvider.OpenAI.findModel("gpt-5.2-pro"), "xhigh"),
      Triple("anthropic", VerityProvider.Anthropic.findModel("claude-opus-4-6"), "max"),
    )
    cases.forEach { (backend, model, effort) ->
      val bodies = mutableListOf<String>()
      val http = capturingHttpClient(bodies)
      val client = when (backend) {
        "openai-chat", "openai-responses" -> OpenAILLMClient(OpenAIClientSettings(), http)
        else -> AnthropicLLMClient(AnthropicClientSettings(), http)
      }
      val params = when (backend) {
        "openai-chat" -> resolveReasoningEffort(VerityProvider.OpenAI, model, OPENAI_CHAT_BACKEND, effort)
        "openai-responses" -> resolveReasoningEffort(VerityProvider.OpenAI, model, OPENAI_RESPONSES_BACKEND, effort)
        else -> resolveReasoningEffort(VerityProvider.Anthropic, model, ANTHROPIC_MESSAGES_BACKEND, effort)
      }

      runCatching { client.execute(prompt("effort-test", params = params) { user("hello") }, model, emptyList()) }
      val body = bodies.single()
      val expectedField = when (backend) {
        "openai-chat" -> "\"reasoning_effort\":\"xhigh\""
        "openai-responses" -> "\"reasoning\":{\"effort\":\"xhigh\"}"
        else -> "\"output_config\":{\"effort\":\"max\"}"
      }
      assertThat(body).contains(expectedField)
      assertThat(body).doesNotContain("thinking")
    }
  }

  @Test
  fun `Koog clients omit native effort fields when unset`() = runTest {
    val cases = listOf(
      VerityProvider.OpenAI to VerityProvider.OpenAI.findModel("gpt-5.2"),
      VerityProvider.OpenAI to VerityProvider.OpenAI.findModel("gpt-5.2-pro"),
      VerityProvider.Anthropic to VerityProvider.Anthropic.findModel("claude-opus-4-6"),
    )
    cases.forEach { (provider, model) ->
      val bodies = mutableListOf<String>()
      val http = capturingHttpClient(bodies)
      val client: LLMClient = when (provider) {
        VerityProvider.OpenAI -> OpenAILLMClient(OpenAIClientSettings(), http)
        else -> AnthropicLLMClient(AnthropicClientSettings(), http)
      }
      runCatching { client.execute(prompt("effort-test", params = LLMParams()) { user("hello") }, model, emptyList()) }
      val body = bodies.single()
      assertThat(body).doesNotContain("reasoning_effort")
      assertThat(body).doesNotContain("\"reasoning\"")
      assertThat(body).doesNotContain("output_config")
    }
  }

  private fun assertUnsupported(providerName: String, modelId: String, backendId: String?, effort: String) {
    val provider = provider(providerName)
    val model = provider.findModel(modelId)
    assertFailsWith<UnsupportedReasoningEffortException> {
      resolveReasoningEffort(provider, model, backendId, effort)
    }
  }

  private fun provider(name: String): VerityProvider = when (name) {
    "openai" -> VerityProvider.OpenAI
    "anthropic" -> VerityProvider.Anthropic
    "google" -> VerityProvider.Google
    else -> error("Unexpected test provider: $name")
  }

  private data class Capability(
    val provider: String,
    val model: String,
    val backendId: String,
    val efforts: Set<String>,
    val property: String,
  )
}

private fun capturingHttpClient(bodies: MutableList<String>): KoogHttpClient = java.lang.reflect.Proxy.newProxyInstance(
  KoogHttpClient::class.java.classLoader,
  arrayOf(KoogHttpClient::class.java),
) { _, method, arguments ->
  when (method.name) {
    "getClientName" -> "local-capture"

    "post" -> {
      bodies += arguments?.getOrNull(1).toString()
      throw IllegalStateException("Captured offline POST")
    }

    "close" -> Unit

    else -> error("Unexpected offline HTTP operation: ${method.name}")
  }
} as KoogHttpClient
