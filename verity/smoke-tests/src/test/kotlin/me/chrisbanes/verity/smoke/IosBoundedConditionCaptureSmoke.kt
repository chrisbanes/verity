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
@Tag("ios")
class IosBoundedConditionCaptureSmoke {
  companion object {
    private lateinit var lifecycle: DeviceLifecycle
    private lateinit var session: DeviceSession

    @BeforeAll @JvmStatic
    fun boot() {
      lifecycle = runBlocking { DeviceLifecycle.discoverOrBootIos() }
      session = runBlocking { lifecycle.connect() }
    }

    @AfterAll @JvmStatic
    fun shutdown() {
      try {
        if (::session.isInitialized) session.close()
      } finally {
        if (::lifecycle.isInitialized) lifecycle.close()
        println("IosBoundedConditionCaptureSmoke session_and_owned_lifecycle_close_completed=true")
      }
    }
  }

  @Test
  fun `factory screenshots join observed production cancellation before bounded and noarg reuse`() = runBlocking {
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-native-screenshot") }
    val output = directory.resolve("capture.png")
    fun inProductionCapture(): Boolean = Thread.getAllStackTraces().values.any { frames -> frames.any { it.className.startsWith("me.chrisbanes.verity.device.ios.BoundedIosScreenshotCapture") } && frames.any { it.methodName.contains("read", ignoreCase = true) || it.methodName == "execute" } }
    val bodyFailure = CompletableDeferred<Throwable?>()
    var invocation: kotlinx.coroutines.Deferred<Unit>? = null
    try {
      session.captureHierarchyTree()
      session.captureHierarchyTree(2.seconds)
      session.captureScreenshot(output)
      assertThat(withContext(Dispatchers.IO) { Files.size(output) > 0 }).isTrue()
      session.captureScreenshot(output, 2.seconds)
      assertThat(withContext(Dispatchers.IO) { Files.size(output) > 0 }).isTrue()
      val prior = withContext(Dispatchers.IO) { Files.readAllBytes(output).toList() }
      val caller = CancellationException("Ios screenshot caller qualification")
      invocation = async(start = CoroutineStart.UNDISPATCHED) {
        try {
          session.captureScreenshot(output, 2.seconds)
          bodyFailure.complete(null)
        } catch (failure: Throwable) {
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
      println("IosBoundedConditionCaptureSmoke sampled exclusive production screenshot worker")
      active.cancel(caller)
      active.join()
      assertThat(bodyFailure.isCompleted).isTrue()
      val observed = bodyFailure.await()
      assertThat(observed === caller || (observed?.javaClass == caller.javaClass && observed.message == caller.message && observed.cause === caller)).isTrue()
      assertThat(withContext(Dispatchers.IO) { inProductionCapture() }).isEqualTo(false)
      assertThat(withContext(Dispatchers.IO) { Files.readAllBytes(output).toList() }).isEqualTo(prior)
      println("IosBoundedConditionCaptureSmoke caller_body_join_and_file_rollback_verified=true")
      session.captureScreenshot(output, 2.seconds)
      session.captureScreenshot(output)
      session.captureHierarchyTree(2.seconds)
      session.captureHierarchyTree()
      assertThat(withContext(Dispatchers.IO) { Files.size(output) > 0 }).isTrue()
      withContext(Dispatchers.IO) { Files.list(directory).use { assertThat(it.toList()).isEqualTo(listOf(output)) } }
      println("IosBoundedConditionCaptureSmoke bounded_and_noarg_screenshot_hierarchy_reuse_verified=true")
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
