package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlin.test.Test
import kotlin.test.assertFailsWith

class VerityProviderTest {
  @Test
  fun `resolves anthropic by name`() {
    val provider = VerityProvider.fromName("anthropic")
    assertThat(provider).isInstanceOf<VerityProvider.Anthropic>()
    assertThat(provider.envVar).isEqualTo("ANTHROPIC_API_KEY")
  }

  @Test
  fun `resolves openai by name`() {
    val provider = VerityProvider.fromName("openai")
    assertThat(provider).isInstanceOf<VerityProvider.OpenAI>()
    assertThat(provider.envVar).isEqualTo("OPENAI_API_KEY")
  }

  @Test
  fun `resolves ollama by name`() {
    val provider = VerityProvider.fromName("ollama")
    assertThat(provider).isInstanceOf<VerityProvider.Ollama>()
    assertThat(provider.requiresAuth).isEqualTo(false)
  }

  @Test
  fun `all 9 providers registered`() {
    assertThat(VerityProvider.all.size).isEqualTo(9)
  }

  @Test
  fun `unknown provider throws`() {
    assertFailsWith<IllegalStateException> {
      VerityProvider.fromName("nonexistent")
    }
  }

  @Test
  fun `findModel returns matching model`() {
    val provider = VerityProvider.fromName("anthropic")
    val model = provider.findModel("claude-haiku-4-5")
    assertThat(model).isNotNull()
    assertThat(model.id).isEqualTo("claude-haiku-4-5")
  }

  @Test
  fun `findModel throws for unknown model`() {
    val provider = VerityProvider.fromName("anthropic")
    assertFailsWith<IllegalStateException> {
      provider.findModel("nonexistent-model")
    }
  }

  @Test
  fun `each provider has valid default models`() {
    VerityProvider.all.forEach { provider ->
      assertThat(provider.defaultNavigatorModel.id).isNotNull()
      assertThat(provider.defaultInspectorModel.id).isNotNull()
    }
  }
}
