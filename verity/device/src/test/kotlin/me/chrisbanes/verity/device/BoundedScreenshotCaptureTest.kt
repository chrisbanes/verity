package me.chrisbanes.verity.device

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.Sink
import okio.Timeout

class BoundedScreenshotCaptureTest {
  @Test fun `fractional millisecond owned budget is not rounded up and preserves earlier output`() = runTest {
    fixture { output ->
      val failure = runCatching {
        captureBoundedScreenshot(output, 500.microseconds, checkpoint = {
          val start = System.nanoTime()
          while (System.nanoTime() - start < 1_000_000) { /* bounded fixture work */ }
        }) { sink, _, _ -> sink.write(Buffer().writeUtf8("late"), 4) }
      }.exceptionOrNull()
      assertThat(failure is ScreenshotCaptureTimeoutException).isEqualTo(true)
      assertThat(Files.readString(output)).isEqualTo("prior")
      assertOnlyOutput(output)
    }
  }

  @Test
  fun `complete bytes replace prior output and staging is removed`() = runTest {
    fixture { output ->
      val bytes = ByteArray(33001) { it.toByte() }
      captureBoundedScreenshot(output, 2.seconds) { sink, _, _ -> sink.write(Buffer().write(bytes), bytes.size.toLong()) }
      assertThat(Files.readAllBytes(output).toList()).isEqualTo(bytes.toList())
      assertOnlyOutput(output)
    }
  }

  @Test
  fun `empty failed and foreign cancelled writers preserve prior output`() = runTest {
    fixture { output ->
      val foreign = runCatching { withTimeout(1.milliseconds) { awaitCancellation() } }.exceptionOrNull()!!
      for (failure in listOf(null, IllegalStateException("writer failed"), CancellationException("foreign stop"), foreign)) {
        val observed = runCatching {
          captureBoundedScreenshot(output, 2.seconds) { sink, _, _ ->
            if (failure != null) {
              sink.write(Buffer().writeUtf8("partial"), 7)
              throw failure
            }
          }
        }.exceptionOrNull()
        if (failure == null) {
          assertThat(observed is IllegalStateException).isEqualTo(true)
        } else {
          assertThat(observed?.javaClass).isEqualTo(failure.javaClass)
          assertThat(observed?.message).isEqualTo(failure.message)
        }
        assertThat(currentCoroutineContext().isActive).isEqualTo(true)
        assertThat(Files.readString(output)).isEqualTo("prior")
        assertOnlyOutput(output)
      }
    }
  }

  @Test
  fun `failure after publication restores old bytes or removes only new output`() = runTest {
    fixture { output ->
      for (existing in listOf(true, false)) {
        if (!existing) Files.delete(output)
        val failure = IllegalStateException("post-publish failure")
        val observed = runCatching {
          captureBoundedScreenshot(output, 2.seconds, onEvent = { if (it == "published") throw failure }) { sink, _, _ ->
            sink.write(Buffer().writeUtf8("replacement"), 11)
          }
        }.exceptionOrNull()
        assertThat(observed?.javaClass).isEqualTo(failure.javaClass)
        assertThat(observed?.message).isEqualTo(failure.message)
        assertThat(Files.exists(output)).isEqualTo(existing)
        if (existing) assertThat(Files.readString(output)).isEqualTo("prior")
        Files.list(output.parent).use { assertThat(it.count()).isEqualTo(if (existing) 1L else 0L) }
      }
    }
  }

  @Test
  fun `cleanup consumes owned deadline and caller cancellation takes priority before admission`() = runTest {
    fixture { output ->
      supervisorScope {
        for (caller in listOf(false, true)) {
          val entered = CountDownLatch(1)
          val release = CountDownLatch(1)
          val accepted = AtomicBoolean()
          val capture = async {
            captureBoundedScreenshot(output, if (caller) 5.seconds else 80.milliseconds, onEvent = {
              if (it == "capture-closed") {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
              }
            }) { sink, _, _ -> sink.write(Buffer().writeUtf8("new"), 3) }
            accepted.set(true)
          }
          try {
            check(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            if (caller) {
              capture.cancel(CancellationException("caller during cleanup"))
            } else {
              withContext(Dispatchers.IO) { Thread.sleep(100) }
            }
            assertThat(capture.isCompleted).isEqualTo(false)
            release.countDown()
            capture.join()
            val failure = runCatching { capture.await() }.exceptionOrNull()
            if (caller) {
              assertThat(failure?.message).isEqualTo("caller during cleanup")
            } else {
              assertThat(failure is ScreenshotCaptureTimeoutException).isEqualTo(true)
            }
            assertThat(accepted.get()).isEqualTo(false)
            assertThat(Files.readString(output)).isEqualTo("prior")
            assertOnlyOutput(output)
          } finally {
            release.countDown()
            capture.cancel()
            capture.join()
          }
        }
      }
    }
  }

  @Test
  fun `checked sink checks chunk writes and always closes once after cancellation`() {
    val writes = ArrayList<Long>()
    var checks = 0
    var closes = 0
    val delegate = object : Sink {
      override fun write(source: Buffer, byteCount: Long) {
        writes.add(byteCount)
        source.skip(byteCount)
      }
      override fun flush() = Unit
      override fun timeout() = Timeout.NONE
      override fun close() {
        closes++
      }
    }
    val sink = CheckedScreenshotSink(delegate) { checks++ }
    sink.write(Buffer().write(ByteArray(17000)), 17000)
    sink.flush()
    sink.close()
    sink.close()
    assertThat(writes).isEqualTo(listOf(8192L, 8192L, 616L))
    assertThat(checks).isEqualTo(10)
    assertThat(closes).isEqualTo(1)
    val stop = CancellationException("close checkpoint")
    val cancelled = CheckedScreenshotSink(delegate) { throw stop }
    assertThat(runCatching { cancelled.close() }.exceptionOrNull()).isEqualTo(stop)
    cancelled.close()
    assertThat(closes).isEqualTo(2)
  }

  @Test
  fun `invalid duration and unknown implementation make zero screenshot calls`() = runTest {
    fixture { output ->
      var calls = 0
      val session = object : DeviceSession by FakeDeviceSession() {
        override suspend fun captureScreenshot(output: Path) {
          calls++
          error("No fallback")
        }
      }
      assertThat(runCatching { session.captureScreenshot(output, 1.seconds) }.exceptionOrNull() is UnsupportedOperationException).isEqualTo(true)
      for (budget in listOf(Duration.ZERO, (-1).milliseconds, Duration.INFINITE)) {
        assertThat(runCatching { captureBoundedScreenshot(output, budget) { _, _, _ -> calls++ } }.exceptionOrNull() is IllegalArgumentException).isEqualTo(true)
      }
      assertThat(calls).isEqualTo(0)
      assertThat(Files.readString(output)).isEqualTo("prior")
      assertOnlyOutput(output)
    }
  }

  private suspend fun fixture(body: suspend (Path) -> Unit) = withContext(Dispatchers.Default) {
    val directory = Files.createTempDirectory("bounded-screenshot-test")
    val output = directory.resolve("image.png")
    Files.writeString(output, "prior")
    try {
      body(output)
    } finally {
      Files.list(directory).use { paths -> paths.forEach(Files::deleteIfExists) }
      Files.delete(directory)
    }
  }

  private fun assertOnlyOutput(output: Path) {
    Files.list(output.parent).use { assertThat(it.toList()).isEqualTo(listOf(output)) }
  }
}
