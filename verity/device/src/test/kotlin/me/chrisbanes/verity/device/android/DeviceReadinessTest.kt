package me.chrisbanes.verity.device.android

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.messageContains
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class DeviceReadinessTest {
  private val awake = "  mWakefulness=Awake"
  private val appFocus = "  mCurrentFocus=Window{1 u0 com.example/com.example.MainActivity}"
  private val dreamFocus =
    "  mCurrentFocus=Window{a1b u0 com.brdgwtr.screensaver/com.brdgwtr.screensaver.DreamActivity}"

  @Test fun awakeWithAppFocusIsReady() = assertThat(parseDeviceReadiness(awake, appFocus)).isNull()

  @Test fun dreamingAsleepAndDozingAreNotReady() {
    for (state in listOf("Dreaming", "Asleep", "Dozing")) {
      assertThat(parseDeviceReadiness("mWakefulness=$state", appFocus)).isNotNull().contains(state.lowercase())
    }
  }

  @Test fun dreamActivityFocusIsNotReady() = assertThat(parseDeviceReadiness(awake, dreamFocus)).isNotNull().contains("com.brdgwtr.screensaver/")

  @Test fun appActivityContainingDreamIsReady() {
    for (name in listOf("com.example/com.example.MyDreamActivity", "com.dream.app/.DreamerActivity")) {
      assertThat(parseDeviceReadiness(awake, "  mCurrentFocus=Window{1 u0 $name}")).isNull()
    }
  }

  @Test fun systemUiSomnambulatorIsNotReady() = assertThat(
    parseDeviceReadiness(awake, "  mCurrentFocus=Window{1 u0 com.android.systemui/com.android.systemui.Somnambulator}"),
  ).isNotNull()

  @Test fun unknownOutputProceeds() {
    assertThat(parseDeviceReadiness("", "")).isNull()
    assertThat(parseDeviceReadiness(awake, "  mCurrentFocus=null")).isNull()
  }

  private class FakeShell(private val power: () -> String, private val focus: () -> String) {
    val commands = mutableListOf<String>()
    val sleeps = mutableListOf<Long>()
    fun run(command: String): String {
      commands += command
      return when {
        command.startsWith("dumpsys power") -> power()
        command.startsWith("dumpsys window") -> focus()
        else -> ""
      }
    }
    fun wakeRounds() = commands.count { it == "input keyevent KEYCODE_WAKEUP" }
  }

  @Test fun awakeDeviceSendsNoWake() = runTest {
    val shell = FakeShell({ awake }, { appFocus })
    ensureAwake(shell::run, shell.sleeps::add)
    assertThat(shell.wakeRounds()).isEqualTo(0)
    assertThat(shell.commands.none { it.contains("stop-dreaming") }).isEqualTo(true)
  }

  @Test fun dreamingDeviceIsWoken() = runTest {
    lateinit var shell: FakeShell
    shell = FakeShell({ if (shell.wakeRounds() == 0) "mWakefulness=Dreaming" else awake }, { appFocus })
    ensureAwake(shell::run, shell.sleeps::add)
    val wake = shell.commands.filter { it.startsWith("input") || it.startsWith("cmd") }
    assertThat(wake).isEqualTo(listOf("input keyevent KEYCODE_WAKEUP", "cmd dreams stop-dreaming"))
  }

  @Test fun stuckScreensaverThrowsAfterThreeAttempts() = runTest {
    val shell = FakeShell({ awake }, { dreamFocus })
    assertFailure { ensureAwake(shell::run, shell.sleeps::add) }
      .isInstanceOf(DeviceNotReadyException::class)
      .messageContains("DreamActivity")
    assertThat(shell.wakeRounds()).isEqualTo(3)
  }

  @Test fun emptyOutputProceedsWithoutWake() = runTest {
    val shell = FakeShell({ "" }, { "" })
    ensureAwake(shell::run, shell.sleeps::add)
    assertThat(shell.wakeRounds()).isEqualTo(0)
  }
}
