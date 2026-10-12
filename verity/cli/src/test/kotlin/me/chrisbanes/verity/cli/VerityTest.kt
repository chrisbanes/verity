package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import me.chrisbanes.verity.core.model.Platform

class VerityTest {
  @Test
  fun `prints help message`() {
    val result = Verity()
      .subcommands(RunCommand(), ListCommand(), McpCommand())
      .test("--help")
    assertThat(result.output).contains("verity")
    assertThat(result.output).contains("run")
    assertThat(result.output).contains("list")
    assertThat(result.output).contains("mcp")
    assertThat(result.output).contains("--provider")
    assertThat(result.output).contains("--navigator-model")
    assertThat(result.output).contains("--inspector-model")
    assertThat(result.output).contains("--navigator-effort")
    assertThat(result.output).contains("--inspector-effort")
    assertThat(result.output).contains("--require-context")
  }

  @Test
  fun `list command shows help`() {
    val result = Verity()
      .subcommands(RunCommand(), ListCommand(), McpCommand())
      .test("list --help")
    assertThat(result.output).contains("journey")
  }

  @Test
  fun `platform option accepts wire names`() {
    for (platform in Platform.entries) {
      val cli = Verity()
      val result = cli.test("--platform ${platform.wireName}")
      assertThat(result.statusCode).isEqualTo(0)
      assertThat(cli.platform).isEqualTo(platform)
    }
  }

  @Test
  fun `platform option defaults to null`() {
    val cli = Verity()
    cli.test("")
    assertThat(cli.platform).isNull()
  }

  @Test
  fun `platform option rejects enum constant names and unknown values`() {
    for (value in listOf("android_tv", "ANDROID_MOBILE", "IOS", "bogus")) {
      val result = Verity().test("--platform $value")
      assertThat(result.statusCode).isEqualTo(1)
      assertThat(result.output).contains("--platform")
      assertThat(result.output).contains("android-tv, android, ios")
    }
  }

  @Test
  fun `help lists accepted platform names`() {
    val help = Verity().test("--help").output.replace(Regex("\\s+"), " ")
    assertThat(help).contains("--platform=<value> Override target platform: android-tv, android, ios")
  }
}
