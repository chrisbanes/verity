package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.chrisbanes.verity.device.DeviceSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag

@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
@Tag("android")
class AndroidBoundedConditionCaptureSmoke {
  companion object {
    private lateinit var lifecycle: DeviceLifecycle
    private lateinit var session: DeviceSession

    @BeforeAll @JvmStatic
    fun boot() {
      lifecycle = runBlocking { DeviceLifecycle.discoverOrBootAndroid() }
      session = runBlocking { lifecycle.connect() }
      runBlocking { withContext(Dispatchers.IO) { recordIdentity() } }
    }

    private fun recordIdentity() {
      val process = ProcessBuilder("adb", "get-serialno").redirectErrorStream(true).start()
      try {
        check(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { "Owned identity query timed out" }
        val serial = process.inputStream.bufferedReader().readText().trim()
        check(process.exitValue() == 0 && serial.startsWith("emulator-")) { "Expected the configured job-owned emulator" }
        println("AndroidBoundedConditionCaptureSmoke device_identity=$serial platform=${session.platform}")
      } finally {
        if (process.isAlive) {
          process.destroy()
          if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
          check(process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) { "Owned identity query did not exit" }
        }
      }
    }

    @AfterAll @JvmStatic
    fun shutdown() {
      try {
        if (::session.isInitialized) session.close()
      } finally {
        if (::lifecycle.isInitialized) lifecycle.close()
        println("AndroidBoundedConditionCaptureSmoke session_and_owned_lifecycle_close_completed=true")
      }
    }
  }

  @Test
  fun `integrated waits capture native state without actions and join before reuse`() = runBlocking {
    qualifyConditionWaits(session, "com.android.settings", "AndroidBoundedConditionCaptureSmoke")
  }

  @Test
  fun `factory screenshots join observed production cancellation before bounded and noarg reuse`() = runBlocking {
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-native-screenshot") }
    val output = directory.resolve("capture.png")
    fun inProductionCapture(): Boolean = Thread.getAllStackTraces().values.any { frames -> frames.any { it.className.startsWith("maestro.") && it.methodName.contains("takeScreenshot") } && frames.any { it.className.startsWith("me.chrisbanes.verity.device.android.AndroidDeviceSession") } }
    val bodyFailure = CompletableDeferred<Throwable?>()
    val bodyCompletedAt = java.util.concurrent.atomic.AtomicLong()
    var invocation: kotlinx.coroutines.Deferred<Unit>? = null
    try {
      session.captureHierarchyTree()
      session.captureHierarchyTree(2.seconds)
      session.captureScreenshot(output)
      assertThat(withContext(Dispatchers.IO) { Files.size(output) > 0 }).isTrue()
      session.captureScreenshot(output, 2.seconds)
      assertThat(withContext(Dispatchers.IO) { Files.size(output) > 0 }).isTrue()
      val prior = withContext(Dispatchers.IO) { Files.readAllBytes(output).toList() }
      val caller = CancellationException("Android screenshot caller qualification")
      val entryAt = System.nanoTime()
      invocation = async(start = CoroutineStart.UNDISPATCHED) {
        try {
          session.captureScreenshot(output, 2.seconds)
          bodyFailure.complete(null)
        } catch (failure: Throwable) {
          bodyCompletedAt.set(System.nanoTime())
          bodyFailure.complete(failure)
          throw failure
        }
      }
      val active = invocation
      withTimeout(2.seconds) {
        while (!withContext(Dispatchers.IO) { inProductionCapture() }) {
          check(!active.isCompleted) { "Screenshot finished before native in-flight qualification" }
          delay(1)
        }
      }
      println("AndroidBoundedConditionCaptureSmoke sampled exclusive production screenshot worker")
      val cancelAt = System.nanoTime()
      active.cancel(caller)
      active.join()
      val joinAt = System.nanoTime()
      assertThat(bodyFailure.isCompleted).isTrue()
      val observed = bodyFailure.await()
      assertThat(observed === caller || (observed?.javaClass == caller.javaClass && observed.message == caller.message && observed.cause === caller)).isTrue()
      assertThat(withContext(Dispatchers.IO) { inProductionCapture() }).isEqualTo(false)
      assertThat(withContext(Dispatchers.IO) { Files.readAllBytes(output).toList() }).isEqualTo(prior)
      println("AndroidBoundedConditionCaptureSmoke caller_body_join_and_file_rollback_verified=true entry_ns=$entryAt cancel_ns=$cancelAt body_complete_ns=${bodyCompletedAt.get()} join_ns=$joinAt active_after_join=0")
      session.captureScreenshot(output, 2.seconds)
      session.captureScreenshot(output)
      session.captureHierarchyTree(2.seconds)
      session.captureHierarchyTree()
      assertThat(withContext(Dispatchers.IO) { Files.size(output) > 0 }).isTrue()
      withContext(Dispatchers.IO) { Files.list(directory).use { assertThat(it.toList()).isEqualTo(listOf(output)) } }
      println("AndroidBoundedConditionCaptureSmoke bounded_and_noarg_screenshot_hierarchy_reuse_verified=true reuse_complete_ns=${System.nanoTime()} head=${System.getenv("GITHUB_SHA")} run=${System.getenv("GITHUB_RUN_ID")} runtime=${System.getProperty("java.version")}/${System.getProperty("os.arch")}")
    } finally {
      withContext(NonCancellable) {
        invocation?.cancelAndJoin()
        withContext(Dispatchers.IO) {
          Files.list(directory).use { paths -> paths.forEach(Files::deleteIfExists) }
          Files.delete(directory)
        }
      }
    }
  }
}
