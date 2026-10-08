package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.model.Platform

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PackagedRuntimeProbeTest {
  @Test
  fun `stage receipts retain order result and monotonic elapsed without payload`() = runTest {
    val records = mutableListOf<String>()
    val payload = Any()
    for (stage in listOf("noarg-first", "bounded-first", "key", "noarg-after-key", "caller-cancel", "reuse")) {
      val result = probeStage(Platform.IOS, stage, records::add, { testScheduler.currentTime * 1_000_000 }) {
        delay(25)
        payload
      }
      assertThat(result).isSameInstanceAs(payload)
      assertThat(records[records.size - 2]).contains("stage=$stage status=start elapsed_nanos=0 failure=none")
      assertThat(records.last()).contains("stage=$stage status=response elapsed_nanos=25000000 failure=none")
    }
    assertThat(records.size).isEqualTo(12)
  }

  @Test
  fun `failure retains original throwable and only cause and suppressed identities`() = runTest {
    val records = mutableListOf<String>()
    val failure = IllegalStateException("private hierarchy payload", IllegalArgumentException("private cause"))
    failure.addSuppressed(UnsupportedOperationException("private suppressed"))
    var observed: Throwable? = null
    try {
      probeStage(Platform.IOS, "bounded-first", records::add) { throw failure }
    } catch (caught: Throwable) {
      observed = caught
    }
    assertThat(observed).isSameInstanceAs(failure)
    assertThat(records.last()).contains("status=failed")
    assertThat(records.last()).contains("failure=java.lang.IllegalStateException causes=java.lang.IllegalArgumentException suppressed=java.lang.UnsupportedOperationException")
    assertThat(records.any { "private" in it }).isEqualTo(false)
  }

  @Test
  fun `caller cancellation preserves exact identity and failure stage`() = runTest {
    val records = mutableListOf<String>()
    val cancellation = CancellationException("caller stop")
    var observed: Throwable? = null
    try {
      probeStage(Platform.IOS, "caller-cancel", records::add) { throw cancellation }
    } catch (caught: Throwable) {
      observed = caught
    }
    assertThat(observed).isSameInstanceAs(cancellation)
    assertThat(records.last()).contains("stage=caller-cancel status=failed")
  }
}
