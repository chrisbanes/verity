package me.chrisbanes.verity.device

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.lang.reflect.Proxy
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
import maestro.TreeNode
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.android.AndroidDeviceSession

class BoundedHierarchyCaptureTest {
  @Test
  fun `default bounded operation rejects unknown native implementations without capture`() = runTest {
    var captures = 0
    val session = object : DeviceSession by FakeDeviceSession() {
      override suspend fun captureHierarchyTree(): HierarchyNode {
        captures++
        error("No native fallback")
      }
    }
    assertThat(runCatching { session.captureHierarchyTree(1.seconds) }.exceptionOrNull() is UnsupportedOperationException).isEqualTo(true)
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
  fun `owned duration rejects Driver tree returned after interruption`() = runTest {
    exerciseBarrier(owned = true, lateReturn = true)
  }

  @Test
  fun `caller cancellation rejects Driver tree returned after interruption`() = runTest {
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
        session.captureHierarchyTree(if (owned) 1.seconds else 10.seconds).also { accepted.set(true) }
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
          assertThat(failure is HierarchyCaptureTimeoutException).isEqualTo(true)
        } else {
          assertThat(failure is CancellationException && failure !is HierarchyCaptureTimeoutException && failure !is TimeoutCancellationException).isEqualTo(true)
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
    for (foreign in listOf(CancellationException("foreign Driver cancellation"), nestedTimeout, IllegalStateException("Driver capture failure"))) {
      withContext(Dispatchers.Default) {
        supervisorScope {
          val barrier = DriverBarrier(foreign = foreign)
          val session = barrier.session()
          val invocation = async { session.captureHierarchyTree(10.seconds) }
          try {
            invocation.join()
            val failure = runCatching { invocation.await() }.exceptionOrNull()
            assertThat(failure?.javaClass).isEqualTo(foreign.javaClass)
            assertThat(failure?.message).isEqualTo(foreign.message)
            assertThat(failure is HierarchyCaptureTimeoutException).isEqualTo(false)
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
        assertThat(runCatching { session.captureHierarchyTree(budget) }.exceptionOrNull() is IllegalArgumentException).isEqualTo(true)
      }
      assertThat(barrier.calls.get()).isEqualTo(0)
      assertThat(barrier.active.get()).isEqualTo(0)
    } finally {
      withContext(NonCancellable + Dispatchers.IO) { session.close() }
    }
  }

  private suspend fun reuse(session: AndroidDeviceSession, barrier: DriverBarrier) {
    assertThat(session.captureHierarchyTree(2.seconds).attributes["bounds"]).isEqualTo("[0,0][100,100]")
    assertThat(barrier.active.get()).isEqualTo(0)
    assertThat(session.captureHierarchyTree().attributes["bounds"]).isEqualTo("[0,0][100,100]")
    assertThat(barrier.active.get()).isEqualTo(0)
    assertThat(barrier.exits.get()).isEqualTo(3)
    assertThat(barrier.peak.get()).isEqualTo(1)
  }

  /** Test-owned Driver barrier; executes through Maestro's actual runInterruptible block. */
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
      val driver = Proxy.newProxyInstance(Driver::class.java.classLoader, arrayOf(Driver::class.java)) { _, method, _ ->
        when (method.name) {
          "deviceInfo" -> DeviceInfo(maestro.device.Platform.ANDROID, 100, 100, 100, 100)

          "contentDescriptor" -> {
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
              TreeNode(attributes = mutableMapOf("bounds" to "[0,0][100,100]"))
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
