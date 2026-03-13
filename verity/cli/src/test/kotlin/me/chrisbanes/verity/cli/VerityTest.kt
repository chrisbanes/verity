package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test

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
  }

  @Test
  fun `list command shows help`() {
    val result = Verity()
      .subcommands(RunCommand(), ListCommand(), McpCommand())
      .test("list --help")
    assertThat(result.output).contains("journey")
  }
}
