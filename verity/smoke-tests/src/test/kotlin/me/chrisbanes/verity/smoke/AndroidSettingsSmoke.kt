package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.Orchestrator
import me.chrisbanes.verity.agent.modelReply
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.result.TrailGranularity
import me.chrisbanes.verity.device.DeviceSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag

@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
@Tag("android")
class AndroidSettingsSmoke {
  companion object {
    private lateinit var lifecycle: DeviceLifecycle
    private lateinit var session: DeviceSession

    @BeforeAll
    @JvmStatic
    fun boot() {
      lifecycle = runBlocking { DeviceLifecycle.discoverOrBootAndroid() }
      session = runBlocking { lifecycle.connect() }
    }

    @AfterAll
    @JvmStatic
    fun shutdown() {
      var sessionClosed = false
      try {
        if (::session.isInitialized) session.close()
        sessionClosed = true
      } finally {
        if (::lifecycle.isInitialized) lifecycle.close()
        println("AndroidSettingsSmoke session_close_completed=$sessionClosed lifecycle_close_completed=true")
      }
    }
  }

  @Test
  fun `settings journey passes`() = runBlocking {
    val url = javaClass.classLoader.getResource("android-settings.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))
    val orchestrator = createOrchestrator()
    val result = orchestrator.run(journey)
    val failedSegment = result.segments.firstOrNull { !it.passed }
    assertThat(result.passed, "segment ${failedSegment?.index} failed: ${failedSegment?.reasoning}")
      .isTrue()
    // Focus values depend on device capture timing, so only the entries are asserted.
    assertThat(result.trail?.entries?.any { it.granularity == TrailGranularity.INTERACTION } == true, "trail should record an interaction")
      .isTrue()
  }

  @Test
  fun `scroll journey passes`() = runBlocking {
    val url = javaClass.classLoader.getResource("android-settings-scroll.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))
    val orchestrator = createOrchestrator()
    val result = orchestrator.run(journey)
    val failedSegment = result.segments.firstOrNull { !it.passed }
    assertThat(result.passed, "segment ${failedSegment?.index} failed: ${failedSegment?.reasoning}")
      .isTrue()
  }

  @Test
  fun `production factory bounded capture and cancellation following reuse`() = runBlocking {
    // Factory setup may install/start its driver. The 2s budget applies only to capture.
    val qualified = session
    var capture: RecordedCapture? = null
    try {
      fun nonempty(tree: me.chrisbanes.verity.core.hierarchy.HierarchyNode): Boolean = tree.attributes.isNotEmpty() || tree.states.isNotEmpty() || tree.children.any(::nonempty)
      assertThat(nonempty(qualified.captureHierarchyTree())).isTrue()
      assertThat(nonempty(qualified.captureHierarchyTree(2000.milliseconds))).isTrue()
      qualified.pressKey("BACK")
      assertThat(nonempty(qualified.captureHierarchyTree())).isTrue()
      capture = recordCapture { qualified.captureHierarchyTree(2000.milliseconds) }
      val inFlight = capture
      withTimeout(2000) {
        while (true) {
          val sampled = withContext(Dispatchers.IO) {
            Thread.getAllStackTraces().values.any { frames ->
              frames.any { it.className.startsWith("maestro.") } &&
                frames.any { it.className.startsWith("me.chrisbanes.verity.device.android.AndroidDeviceSession") }
            }
          }
          if (sampled) break
          check(!inFlight.job.isCompleted) { "Capture completed before in-flight worker qualification" }
          delay(1)
        }
      }
      println("AndroidSettingsSmoke sampled exclusive production bounded capture worker")
      inFlight.cancelFromCallerAndVerify()
      println("AndroidSettingsSmoke invocation body confirmed explicit caller cancellation after complete join")
      assertThat(nonempty(qualified.captureHierarchyTree())).isTrue()
    } finally {
      withContext(NonCancellable) {
        capture?.job?.cancelAndJoin()
      }
    }
  }

  private fun createOrchestrator() = Orchestrator(
    session = session,
    navigatorFactory = {
      NavigatorAgent("unused") { _, _ -> modelReply("DOWN") }
    },
    inspectorFactory = {
      InspectorAgent(
        evaluateTreeContent = { _, _, _ ->
          error("VISIBLE mode: inspector should not be called")
        },
        evaluateVisualContent = { _, _, _, _ ->
          error("inspector visual should not be called")
        },
      )
    },
  )
}
