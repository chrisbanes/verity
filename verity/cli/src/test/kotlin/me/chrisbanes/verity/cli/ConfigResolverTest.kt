package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import kotlin.test.Test
import kotlin.test.assertFailsWith
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.Platform

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

  @Test
  fun `resolved config uses cli values before config values`() {
    val config = VerityConfig(
      paths = VerityPathsConfig(
        journeys = "config-journeys",
        context = "config-context",
        output = "config-output",
      ),
      device = VerityDeviceConfig(
        platform = "android-tv",
        id = "config-device",
        disableAnimations = false,
      ),
      llm = VerityLlmConfig(
        provider = "anthropic",
        navigatorModel = "claude-haiku-4-5",
        inspectorModel = "claude-sonnet-4-5",
      ),
      assertions = VerityAssertionsConfig(strategy = "tree"),
    )

    val resolved = ResolvedProjectConfig.resolve(
      config = config,
      cli = ProjectCliOptions(
        journeysPath = "cli-journeys",
        contextPath = "cli-context",
        outputPath = "cli-output",
        platform = "ios",
        deviceId = "cli-device",
        disableAnimations = true,
        provider = "anthropic",
        navigatorModel = "claude-sonnet-4-5",
        inspectorModel = "claude-opus-4-5",
        assertionStrategy = "visual",
      ),
    )

    assertThat(resolved.journeysPath.path).isEqualTo("cli-journeys")
    assertThat(resolved.contextPath?.path).isEqualTo("cli-context")
    assertThat(resolved.outputPath.path).isEqualTo("cli-output")
    assertThat(resolved.platform).isEqualTo(Platform.IOS)
    assertThat(resolved.deviceId).isEqualTo("cli-device")
    assertThat(resolved.disableAnimations).isEqualTo(true)
    assertThat(resolved.provider.name).isEqualTo("anthropic")
    assertThat(resolved.navigatorModel.id).isEqualTo("claude-sonnet-4-5")
    assertThat(resolved.inspectorModel.id).isEqualTo("claude-opus-4-5")
    assertThat(resolved.assertionStrategy).isEqualTo(AssertionStrategy.VISUAL)
  }

  @Test
  fun `resolved config preserves current defaults when config and cli are empty`() {
    val resolved = ResolvedProjectConfig.resolve(
      config = VerityConfig(),
      cli = ProjectCliOptions(),
    )

    assertThat(resolved.journeysPath.path).isEqualTo(".")
    assertThat(resolved.contextPath).isNull()
    assertThat(resolved.outputPath.path).isEqualTo("build/verity")
    assertThat(resolved.platform).isNull()
    assertThat(resolved.deviceId).isNull()
    assertThat(resolved.disableAnimations).isEqualTo(false)
    assertThat(resolved.provider.name).isEqualTo("anthropic")
    assertThat(resolved.assertionStrategy).isEqualTo(AssertionStrategy.INFER)
  }

  @Test
  fun `invalid platform message names accepted values`() {
    val error = assertFailsWith<IllegalArgumentException> {
      resolvePlatform("watch")
    }

    assertThat(error.message.orEmpty()).contains("device.platform")
    assertThat(error.message.orEmpty()).contains("android-tv")
    assertThat(error.message.orEmpty()).contains("android")
    assertThat(error.message.orEmpty()).contains("ios")
  }

  @Test
  fun `invalid assertion strategy message names accepted values`() {
    val error = assertFailsWith<IllegalArgumentException> {
      resolveAssertionStrategy("expensive")
    }

    assertThat(error.message.orEmpty()).contains("assertions.strategy")
    assertThat(error.message.orEmpty()).contains("infer")
    assertThat(error.message.orEmpty()).contains("visible")
    assertThat(error.message.orEmpty()).contains("focused")
    assertThat(error.message.orEmpty()).contains("tree")
    assertThat(error.message.orEmpty()).contains("visual")
  }

  @Test
  fun `readable directory validation rejects files`() {
    val file = kotlin.io.path.createTempFile("verity-context", ".md").toFile()

    val error = assertFailsWith<IllegalArgumentException> {
      validateReadableDirectory(file, "paths.context")
    }

    assertThat(error.message.orEmpty()).contains("paths.context")
    assertThat(error.message.orEmpty()).contains("must be a directory")
  }

  @Test
  fun `output directory validation rejects existing files`() {
    val file = kotlin.io.path.createTempFile("verity-output", ".txt").toFile()

    val error = assertFailsWith<IllegalArgumentException> {
      validateOutputDirectory(file)
    }

    assertThat(error.message.orEmpty()).contains("paths.output")
    assertThat(error.message.orEmpty()).contains("must be a directory")
  }
}
