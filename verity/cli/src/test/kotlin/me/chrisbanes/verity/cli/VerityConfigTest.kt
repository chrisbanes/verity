package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class VerityConfigTest {
  @Test
  fun `parses full config`() {
    val yaml =
      """
      provider: openai
      navigator-model: gpt-4o-mini
      inspector-model: gpt-4o
      require-context: true
      """.trimIndent()
    val config = VerityConfig.fromYaml(yaml)
    assertThat(config.provider).isEqualTo("openai")
    assertThat(config.navigatorModel).isEqualTo("gpt-4o-mini")
    assertThat(config.inspectorModel).isEqualTo("gpt-4o")
    assertThat(config.requireContext).isEqualTo(true)
  }

  @Test
  fun `parses structured config`() {
    val yaml =
      """
      paths:
        journeys: journeys
        context: context
        output: build/verity
      device:
        platform: android-tv
        id: emulator-5554
        disable-animations: true
      llm:
        provider: anthropic
        navigator-model: claude-haiku-4-5
        inspector-model: claude-sonnet-4-5
      assertions:
        strategy: tree
      """.trimIndent()

    val config = VerityConfig.fromYaml(yaml)

    assertThat(config.paths?.journeys).isEqualTo("journeys")
    assertThat(config.paths?.context).isEqualTo("context")
    assertThat(config.paths?.output).isEqualTo("build/verity")
    assertThat(config.device?.platform).isEqualTo("android-tv")
    assertThat(config.device?.id).isEqualTo("emulator-5554")
    assertThat(config.device?.disableAnimations).isEqualTo(true)
    assertThat(config.llm?.provider).isEqualTo("anthropic")
    assertThat(config.llm?.navigatorModel).isEqualTo("claude-haiku-4-5")
    assertThat(config.llm?.inspectorModel).isEqualTo("claude-sonnet-4-5")
    assertThat(config.assertions?.strategy).isEqualTo("tree")
  }

  @Test
  fun `structured llm values override legacy top-level values`() {
    val config = VerityConfig.fromYaml(
      """
      provider: openai
      navigator-model: gpt-4o-mini
      inspector-model: gpt-4o
      llm:
        provider: anthropic
        navigator-model: claude-haiku-4-5
        inspector-model: claude-sonnet-4-5
      """.trimIndent(),
    )

    assertThat(config.effectiveProvider).isEqualTo("anthropic")
    assertThat(config.effectiveNavigatorModel).isEqualTo("claude-haiku-4-5")
    assertThat(config.effectiveInspectorModel).isEqualTo("claude-sonnet-4-5")
  }

  @Test
  fun `parses partial config with only provider`() {
    val yaml = "provider: google"
    val config = VerityConfig.fromYaml(yaml)
    assertThat(config.provider).isEqualTo("google")
    assertThat(config.navigatorModel).isNull()
    assertThat(config.inspectorModel).isNull()
    assertThat(config.requireContext).isNull()
  }

  @Test
  fun `empty yaml returns empty config`() {
    val config = VerityConfig.fromYaml("{}")
    assertThat(config.provider).isNull()
    assertThat(config.navigatorModel).isNull()
    assertThat(config.inspectorModel).isNull()
    assertThat(config.requireContext).isNull()
  }

  @Test
  fun `loads from missing file returns empty config`() {
    val config = VerityConfig.loadOrDefault(java.io.File("/nonexistent/path/config.yaml"))
    assertThat(config).isEqualTo(VerityConfig())
  }

  @Test
  fun `empty file content returns empty config`() {
    val tempFile = java.io.File.createTempFile("verity-config", ".yaml")
    try {
      tempFile.writeText("")
      val config = VerityConfig.loadOrDefault(tempFile)
      assertThat(config).isEqualTo(VerityConfig())
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun `parses require context config`() {
    val yaml =
      """
      require-context: true
      """.trimIndent()

    val config = VerityConfig.fromYaml(yaml)

    assertThat(config.requireContext).isEqualTo(true)
  }

  @Test
  fun `resolveRequiredContext uses cli flag before config`() {
    assertThat(resolveRequiredContext(cliRequireContext = true, config = VerityConfig(requireContext = false)))
      .isEqualTo(true)
  }

  @Test
  fun `resolveRequiredContext uses config when cli flag is false`() {
    assertThat(resolveRequiredContext(cliRequireContext = false, config = VerityConfig(requireContext = true)))
      .isEqualTo(true)
  }

  @Test
  fun `resolveRequiredContext defaults to false`() {
    assertThat(resolveRequiredContext(cliRequireContext = false, config = VerityConfig()))
      .isEqualTo(false)
  }
}
