package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test

class VerityOptionsTest {
  @Test
  fun `no-animations flag does not consume the subcommand`() {
    val probe = ProbeCommand()
    val result = Verity().subcommands(probe).test("--no-animations run")
    assertThat(result.statusCode).isEqualTo(0)
    assertThat(probe.noAnimations).isEqualTo(true)
  }

  @Test
  fun `no-animations accepts an attached false`() {
    val probe = ProbeCommand()
    Verity().subcommands(probe).test("--no-animations=false run")
    assertThat(probe.noAnimations).isEqualTo(false)
  }

  @Test
  fun `no-animations is unset when omitted`() {
    val probe = ProbeCommand()
    Verity().subcommands(probe).test("run")
    assertThat(probe.noAnimations).isNull()
  }

  private class ProbeCommand : CliktCommand(name = "run") {
    var noAnimations: Boolean? = null

    override fun run() {
      noAnimations = (currentContext.parent!!.command as Verity).noAnimations
    }
  }
}
