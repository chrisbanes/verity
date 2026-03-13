package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test

class ConfigResolverTest {
  @Test
  fun `defaults to anthropic when no config or flags`() {
    val resolved = resolveProvider(
      cliProvider = null,
      config = VerityConfig(),
    )
    assertThat(resolved).isInstanceOf<VerityProvider.Anthropic>()
  }

  @Test
  fun `config overrides default provider`() {
    val resolved = resolveProvider(
      cliProvider = null,
      config = VerityConfig(provider = "openai"),
    )
    assertThat(resolved).isInstanceOf<VerityProvider.OpenAI>()
  }

  @Test
  fun `cli flag overrides config provider`() {
    val resolved = resolveProvider(
      cliProvider = "google",
      config = VerityConfig(provider = "openai"),
    )
    assertThat(resolved).isInstanceOf<VerityProvider.Google>()
  }

  @Test
  fun `resolveModel uses cli flag first`() {
    val provider = VerityProvider.Anthropic
    val model = resolveModel(
      cliModel = "claude-sonnet-4-5",
      configModel = "claude-haiku-4-5",
      default = provider.defaultNavigatorModel,
      provider = provider,
    )
    assertThat(model.id).isEqualTo("claude-sonnet-4-5")
  }

  @Test
  fun `resolveModel uses config when no cli flag`() {
    val provider = VerityProvider.Anthropic
    val model = resolveModel(
      cliModel = null,
      configModel = "claude-sonnet-4-5",
      default = provider.defaultNavigatorModel,
      provider = provider,
    )
    assertThat(model.id).isEqualTo("claude-sonnet-4-5")
  }

  @Test
  fun `resolveModel uses provider default when no cli or config`() {
    val provider = VerityProvider.Anthropic
    val model = resolveModel(
      cliModel = null,
      configModel = null,
      default = provider.defaultNavigatorModel,
      provider = provider,
    )
    assertThat(model.id).isEqualTo(provider.defaultNavigatorModel.id)
  }
}
