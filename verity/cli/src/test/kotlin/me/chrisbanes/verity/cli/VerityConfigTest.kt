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
      """.trimIndent()
    val config = VerityConfig.fromYaml(yaml)
    assertThat(config.provider).isEqualTo("openai")
    assertThat(config.navigatorModel).isEqualTo("gpt-4o-mini")
    assertThat(config.inspectorModel).isEqualTo("gpt-4o")
  }

  @Test
  fun `parses partial config with only provider`() {
    val yaml = "provider: google"
    val config = VerityConfig.fromYaml(yaml)
    assertThat(config.provider).isEqualTo("google")
    assertThat(config.navigatorModel).isNull()
    assertThat(config.inspectorModel).isNull()
  }

  @Test
  fun `empty yaml returns empty config`() {
    val config = VerityConfig.fromYaml("{}")
    assertThat(config.provider).isNull()
    assertThat(config.navigatorModel).isNull()
    assertThat(config.inspectorModel).isNull()
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
}
