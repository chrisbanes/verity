package me.chrisbanes.verity.device

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import maestro.DeviceInfo
import maestro.Driver
import maestro.Maestro
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.android.AndroidDeviceSession
import okio.Buffer
import okio.Sink

class BoundedAndroidScreenshotCaptureTest {
  @Test
  fun `default bounded operation rejects unknown native implementations without capture`() = runTest {
    var captures = 0
    val session = object : DeviceSession by FakeDeviceSession() {
      override suspend fun captureScreenshot(output: Path) {
        captures++
        error("No native fallback")
      }
    }
    assertThat(runCatching { session.captureScreenshot(Files.createTempFile("unsupported-screenshot", ".png").also { Files.delete(it) }, 1.seconds) }.exceptionOrNull() is UnsupportedOperationException).isEqualTo(true)
    assertThat(captures).isEqualTo(0)
  }

  @Test
  fun `owned duration interrupts SDK worker with active parent before sequential reuse`() = runTest {
    exerciseBarrier(owned = true, lateReturn = false)
  }

  @Test
  fun `caller cancellation interrupts SDK worker before sequential reuse`() = runTest {
    exerciseBarrier(owned = false, lateReturn = false)
  }

  @Test
  fun `owned duration rejects Driver screenshot returned after interruption`() = runTest {
    exerciseBarrier(owned = true, lateReturn = true)
  }

  @Test
  fun `caller cancellation rejects Driver screenshot returned after interruption`() = runTest {
    exerciseBarrier(owned = false, lateReturn = true)
  }

  @Test
  fun `caller cancellation wins after owned expiry while Driver remains held`() = runTest {
    exerciseBarrier(owned = true, lateReturn = true, parentWins = true)
  }

  private suspend fun exerciseBarrier(owned: Boolean, lateReturn: Boolean, parentWins: Boolean = false) = withContext(Dispatchers.Default) {
    supervisorScope {
      val barrier = DriverBarrier(lateReturn = lateReturn)
      val session = barrier.session()
      val accepted = AtomicBoolean()
      val invocation = async {
        captureToBytes(session, if (owned) 1.seconds else 10.seconds).also { accepted.set(true) }
      }
      try {
        // Watchdogs only prevent a broken fixture hanging; interruption itself releases the barrier.
        withTimeout(5.seconds) { barrier.entered.await() }
        if (!owned) invocation.cancel(CancellationException("caller barrier stop"))
        withTimeout(5.seconds) { barrier.interrupted.await() }
        if (lateReturn) {
          assertThat(invocation.isCompleted).isEqualTo(false)
          assertThat(barrier.active.get()).isEqualTo(1)
        }
        if (parentWins) invocation.cancel(CancellationException("caller wins after owned expiry"))
        barrier.release.countDown()
        withTimeout(5.seconds) {
          barrier.exited.await()
          invocation.join()
        }
        val failure = runCatching { invocation.await() }.exceptionOrNull()
        if (owned && !parentWins) {
          assertThat(failure is ScreenshotCaptureTimeoutException).isEqualTo(true)
        } else {
          assertThat(failure is CancellationException && failure !is ScreenshotCaptureTimeoutException && failure !is TimeoutCancellationException).isEqualTo(true)
          assertThat(failure?.message).isEqualTo(if (parentWins) "caller wins after owned expiry" else "caller barrier stop")
        }
        assertThat(currentCoroutineContext().isActive).isEqualTo(true)
        assertThat(accepted.get()).isEqualTo(false)
        assertThat(barrier.lateReturns.get()).isEqualTo(if (lateReturn) 1 else 0)
        assertThat(barrier.active.get()).isEqualTo(0)
        assertThat(barrier.exits.get()).isEqualTo(1)
        reuse(session, barrier)
      } finally {
        withContext(NonCancellable + Dispatchers.IO) {
          barrier.release.countDown()
          invocation.cancel()
          invocation.join()
          session.close()
        }
      }
    }
  }

  @Test
  fun `foreign cancellation exceptions retain provenance with active parent and complete exit`() = runTest {
    val nestedTimeout = runCatching { withTimeout(1.milliseconds) { awaitCancellation() } }.exceptionOrNull()!!
    assertThat(nestedTimeout is TimeoutCancellationException).isEqualTo(true)
    for (foreign in listOf(CancellationException("foreign Driver cancellation"), nestedTimeout, IllegalStateException("Driver screenshot failure"))) {
      withContext(Dispatchers.Default) {
        supervisorScope {
          val barrier = DriverBarrier(foreign = foreign)
          val session = barrier.session()
          val invocation = async { captureToBytes(session, 10.seconds) }
          try {
            invocation.join()
            val failure = runCatching { invocation.await() }.exceptionOrNull()
            assertThat(failure?.javaClass).isEqualTo(foreign.javaClass)
            assertThat(failure?.message).isEqualTo(foreign.message)
            assertThat(failure is ScreenshotCaptureTimeoutException).isEqualTo(false)
            assertThat(currentCoroutineContext().isActive).isEqualTo(true)
            assertThat(barrier.active.get()).isEqualTo(0)
            assertThat(barrier.exits.get()).isEqualTo(1)
            reuse(session, barrier)
          } finally {
            withContext(NonCancellable + Dispatchers.IO) { session.close() }
          }
        }
      }
    }
  }

  @Test
  fun `invalid Android durations make zero Driver calls`() = runTest {
    val barrier = DriverBarrier()
    val session = barrier.session()
    try {
      for (budget in listOf(Duration.ZERO, (-1).milliseconds, Duration.INFINITE)) {
        assertThat(runCatching { captureToBytes(session, budget) }.exceptionOrNull() is IllegalArgumentException).isEqualTo(true)
      }
      assertThat(barrier.calls.get()).isEqualTo(0)
      assertThat(barrier.active.get()).isEqualTo(0)
    } finally {
      withContext(NonCancellable + Dispatchers.IO) { session.close() }
    }
  }

  private suspend fun captureToBytes(session: AndroidDeviceSession, timeout: Duration? = null): List<Byte> {
    val output = Files.createTempFile("android-screenshot-test", ".png")
    Files.writeString(output, "prior")
    try {
      if (timeout == null) session.captureScreenshot(output) else session.captureScreenshot(output, timeout)
      return Files.readAllBytes(output).toList()
    } finally {
      Files.deleteIfExists(output)
    }
  }

  private suspend fun reuse(session: AndroidDeviceSession, barrier: DriverBarrier) {
    assertThat(captureToBytes(session, 2.seconds)).isEqualTo(listOf<Byte>(1, 2, 3))
    assertThat(barrier.active.get()).isEqualTo(0)
    assertThat(captureToBytes(session)).isEqualTo(listOf<Byte>(1, 2, 3))
    assertThat(barrier.active.get()).isEqualTo(0)
    assertThat(barrier.exits.get()).isEqualTo(3)
    assertThat(barrier.peak.get()).isEqualTo(1)
  }

  /** Supplemental test-owned Driver barrier through Maestro public screenshot and its actual runInterruptible block; not native qualification. */
  private class DriverBarrier(val lateReturn: Boolean = false, val foreign: Throwable? = null) {
    val entered = CompletableDeferred<Unit>()
    val interrupted = CompletableDeferred<Unit>()
    val exited = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val calls = AtomicInteger()
    val exits = AtomicInteger()
    val lateReturns = AtomicInteger()
    fun session(): AndroidDeviceSession {
      val driver = Proxy.newProxyInstance(Driver::class.java.classLoader, arrayOf(Driver::class.java)) { _, method, arguments ->
        when (method.name) {
          "deviceInfo" -> DeviceInfo(maestro.device.Platform.ANDROID, 100, 100, 100, 100)

          "takeScreenshot" -> {
            assertThat(arguments!![1]).isEqualTo(false)
            val first = calls.incrementAndGet() == 1
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
              if (first) {
                entered.complete(Unit)
                foreign?.let { throw it }
                try {
                  check(release.await(5, TimeUnit.SECONDS)) { "Driver barrier watchdog" }
                } catch (stop: InterruptedException) {
                  interrupted.complete(Unit)
                  if (!lateReturn) throw stop
                  check(release.await(5, TimeUnit.SECONDS)) { "Late-return barrier watchdog" }
                  lateReturns.incrementAndGet()
                }
              }
              val sink = arguments[0] as Sink
              sink.write(Buffer().write(byteArrayOf(1, 2, 3)), 3)
              Unit
            } finally {
              active.decrementAndGet()
              exits.incrementAndGet()
              if (first) exited.complete(Unit)
            }
          }

          "name" -> "owned-duration-barrier"

          "close" -> Unit

          else -> error("Unexpected SDK fixture call: ${method.name}")
        }
      } as Driver
      return AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE) { "" }
    }
  }
}
