package me.chrisbanes.verity.device.ios

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import kotlin.test.Test

class IosDeviceSessionCommandParserTest {

  @Test
  fun `parses simple command args`() {
    assertThat(parseCommandArgs("spawn booted log stream"))
      .isEqualTo(listOf("spawn", "booted", "log", "stream"))
  }

  @Test
  fun `preserves quoted args`() {
    assertThat(parseCommandArgs("spawn booted launchctl print \"system/com.example app\""))
      .isEqualTo(listOf("spawn", "booted", "launchctl", "print", "system/com.example app"))
  }

  @Test
  fun `supports escaped spaces`() {
    assertThat(parseCommandArgs("""spawn booted echo hello\ world"""))
      .isEqualTo(listOf("spawn", "booted", "echo", "hello world"))
  }

  @Test
  fun `rejects unterminated quote`() {
    assertFailure {
      parseCommandArgs("spawn booted \"unterminated")
    }.messageContains("Unterminated quote")
  }

  @Test
  fun `rejects blank commands`() {
    assertFailure {
      parseCommandArgs("   ")
    }.messageContains("Command cannot be blank")
  }
}
