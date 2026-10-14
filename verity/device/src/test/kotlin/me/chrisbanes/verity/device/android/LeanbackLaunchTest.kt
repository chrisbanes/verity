package me.chrisbanes.verity.device.android

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult

class LeanbackLaunchTest {
  private val notFound = "No activity found"
  private val settings = "priority=3 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\ncom.android.tv.settings/.MainSettings"
  private val launcher = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\ncom.example/.MainActivity"

  private class FakeShell(private val launcher: String, private val leanback: String, private val start: String = "Status: ok") {
    val commands = mutableListOf<String>()
    fun run(command: String): String {
      commands += command
      return when {
        command.contains("category.LAUNCHER ") -> launcher
        command.contains("category.LEANBACK_LAUNCHER ") -> leanback
        command.startsWith("am start") -> start
        else -> ""
      }
    }
    fun mutating() = commands.filterNot { it.startsWith("cmd package resolve-activity") }
  }

  private val maestroFlows = mutableListOf<ActionFlow>()
  private suspend fun run(flow: ActionFlow, shell: FakeShell, maestroResult: FlowResult = FlowResult(true)) = executeWithLeanbackLaunch(flow, shell::run) {
    maestroFlows += it
    maestroResult
  }

  @Test fun launcherAppUsesMaestro() = runTest {
    val shell = FakeShell(launcher, notFound)
    val flow = ActionFlow("com.example", listOf(Interaction.LaunchApp()))
    assertThat(run(flow, shell).success).isTrue()
    assertThat(maestroFlows).containsExactly(flow)
    assertThat(shell.mutating()).isEmpty()
  }

  @Test fun flowWithoutLaunchSkipsResolution() = runTest {
    val shell = FakeShell(notFound, notFound)
    val flow = ActionFlow("com.example", listOf(Interaction.KeyPress("Remote Dpad Down")))
    assertThat(run(flow, shell).success).isTrue()
    assertThat(maestroFlows).containsExactly(flow)
    assertThat(shell.commands).isEmpty()
  }

  @Test fun leanbackOnlyAppLaunchesThroughShell() = runTest {
    val shell = FakeShell(notFound, settings)
    val result = run(ActionFlow("com.android.tv.settings", listOf(Interaction.LaunchApp())), shell)
    assertThat(result.success).isTrue()
    assertThat(maestroFlows).isEmpty()
    assertThat(shell.mutating()).containsExactly(
      "am force-stop com.android.tv.settings",
      "am start -W -n com.android.tv.settings/.MainSettings",
    )
  }

  @Test fun clearStateClearsBeforeStart() = runTest {
    val shell = FakeShell(notFound, settings)
    run(ActionFlow("com.android.tv.settings", listOf(Interaction.LaunchApp(clearState = true))), shell)
    assertThat(shell.mutating()).containsExactly(
      "am force-stop com.android.tv.settings",
      "pm clear com.android.tv.settings",
      "am start -W -n com.android.tv.settings/.MainSettings",
    )
  }

  @Test fun actionsAroundLaunchKeepOrder() = runTest {
    val down = Interaction.KeyPress("Remote Dpad Down")
    val shell = FakeShell(notFound, settings)
    run(ActionFlow("com.android.tv.settings", listOf(down, Interaction.LaunchApp(), down)), shell)
    assertThat(maestroFlows).containsExactly(
      ActionFlow("com.android.tv.settings", listOf(down)),
      ActionFlow("com.android.tv.settings", listOf(down)),
    )
  }

  @Test fun failedMaestroBeforeLaunchStopsFlow() = runTest {
    val shell = FakeShell(notFound, settings)
    val down = Interaction.KeyPress("Remote Dpad Down")
    val result = run(ActionFlow("com.android.tv.settings", listOf(down, Interaction.LaunchApp())), shell, FlowResult(false, "boom"))
    assertThat(result).isEqualTo(FlowResult(false, "boom"))
    assertThat(shell.mutating()).isEmpty()
  }

  @Test fun appWithNeitherCategoryFails() = runTest {
    val shell = FakeShell(notFound, notFound)
    val result = run(ActionFlow("com.not.installed", listOf(Interaction.LaunchApp())), shell)
    assertThat(result.success).isFalse()
    assertThat(result.output).contains("No LAUNCHER or LEANBACK_LAUNCHER activity")
    assertThat(maestroFlows).isEmpty()
    assertThat(shell.mutating()).isEmpty()
  }

  @Test fun amStartErrorFails() = runTest {
    val shell = FakeShell(notFound, settings, start = "Error: Activity class {x} does not exist.")
    val result = run(ActionFlow("com.android.tv.settings", listOf(Interaction.LaunchApp())), shell)
    assertThat(result.success).isFalse()
    assertThat(result.output).contains("does not exist")
  }

  @Test fun unsafePackageNameNeverReachesShell() = runTest {
    val shell = FakeShell(launcher, settings)
    val result = run(ActionFlow("com.example; reboot", listOf(Interaction.LaunchApp())), shell)
    assertThat(result.success).isFalse()
    assertThat(shell.commands).isEmpty()
  }
}
