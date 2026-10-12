package me.chrisbanes.verity.device.preflight

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class LocalProcessRunnerTest {
  @Test fun `cancellation stops the child and returns promptly`() = runTest(timeout = 10.seconds) {
    val pidFile = withContext(Dispatchers.IO) { Files.createTempFile("verity-pid-", ".txt") }
    var pid: Long? = null
    try {
      val job = launch { LocalProcessRunner.run(listOf("sh", "-c", "echo \$\$ > \"\$1\"; exec sleep 60", "sh", pidFile.toString())) }
      pid = awaitPid(pidFile)
      job.cancelAndJoin()
      assertThat(job.isCancelled).isTrue()
      assertThat(ProcessHandle.of(pid).map { it.isAlive }.orElse(false)).isFalse()
    } finally {
      withContext(NonCancellable + Dispatchers.IO) {
        pid?.let(::killIfSleep)
        Files.deleteIfExists(pidFile)
      }
    }
  }

  @Test fun `returns exit code and merged output`() = runTest(timeout = 10.seconds) {
    val result = LocalProcessRunner.run(listOf("sh", "-c", "printf out; printf err >&2; exit 3"))
    assertThat(result.exitCode).isEqualTo(3)
    assertThat(result.output).contains("out")
    assertThat(result.output).contains("err")
  }

  @Test fun `returns output larger than a pipe buffer`() = runTest(timeout = 10.seconds) {
    val result = LocalProcessRunner.run(listOf("sh", "-c", "head -c 1048576 /dev/zero"))
    assertThat(result.exitCode).isEqualTo(0)
    assertThat(result.output.length).isEqualTo(1_048_576)
  }

  @Test fun `a descendant holding stdout does not block the result`() = runTest(timeout = 10.seconds) {
    var result: ProcessResult? = null
    try {
      result = LocalProcessRunner.run(listOf("sh", "-c", "sleep 30 & echo \$!"))
      assertThat(result.exitCode).isEqualTo(0)
    } finally {
      withContext(NonCancellable + Dispatchers.IO) {
        result?.output?.trim()?.toLongOrNull()?.let(::killIfSleep)
      }
    }
  }

  @Test fun `missing executable throws IOException`() = runTest(timeout = 10.seconds) {
    assertFailure { LocalProcessRunner.run(listOf("verity-missing-command-147")) }.isInstanceOf<IOException>()
  }

  private suspend fun awaitPid(pidFile: Path): Long = withContext(Dispatchers.IO) {
    var content = pidFile.readText()
    while (!content.endsWith("\n")) {
      delay(10)
      content = pidFile.readText()
    }
    content.trim().toLong()
  }

  /** Kills [pid] only if it is still the `sleep` this test started. */
  private fun killIfSleep(pid: Long) {
    ProcessHandle.of(pid)
      .filter { handle -> handle.info().command().map { it.endsWith("sleep") }.orElse(false) }
      .ifPresent { it.destroyForcibly() }
  }
}
