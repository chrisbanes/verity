package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession

class OrchestratorTest {
  @Test
  fun `classifies all-key-mapped actions as fast path`() {
    val actions = listOf("press d-pad down", "press d-pad down", "press select")
    val isFastPath = Orchestrator.isFastPath(actions, Platform.ANDROID_TV)
    assertThat(isFastPath).isTrue()
  }

  @Test
  fun `classifies non-mappable actions as slow path`() {
    val actions = listOf("press d-pad down", "navigate to settings page")
    val isFastPath = Orchestrator.isFastPath(actions, Platform.ANDROID_TV)
    assertThat(isFastPath).isFalse()
  }

  @Test
  fun `JourneyResult passed is true when all segments pass`() {
    val result = JourneyResult(
      "test",
      listOf(SegmentResult(0, passed = true), SegmentResult(1, passed = true)),
    )
    assertThat(result.passed).isTrue()
  }

  @Test
  fun `JourneyResult passed is false when any segment fails`() {
    val result = JourneyResult(
      "test",
      listOf(SegmentResult(0, passed = true), SegmentResult(1, passed = false)),
    )
    assertThat(result.passed).isFalse()
  }

  @Test
  fun `JourneyResult failedAt returns first failed segment index`() {
    val result = JourneyResult(
      "test",
      listOf(
        SegmentResult(0, passed = true),
        SegmentResult(1, passed = false),
        SegmentResult(2, passed = false),
      ),
    )
    assertThat(result.failedAt).isEqualTo(1)
  }

  @Test
  fun `JourneyResult failedAt is null when all pass`() {
    val result = JourneyResult(
      "test",
      listOf(SegmentResult(0, passed = true)),
    )
    assertThat(result.failedAt).isNull()
  }

  @Test
  fun `run preserves inspector reasoning for tree assertions`() = runTest {
    val session = FakeDeviceSession()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { FakeTextAgent { error("unused") } } },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { """{"passed": false, "reasoning": "Tree mismatch"}""" } },
          evaluateVisualContent = { _, _, _ -> error("unused") },
        )
      },
    )

    val journey = Journey(
      name = "assertion-test",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(
        JourneyStep.Assert(description = "Home is visible", mode = AssertMode.TREE),
      ),
    )

    val result = orchestrator.run(journey)

    assertThat(result.passed).isFalse()
    assertThat(result.segments.single().reasoning).isEqualTo("Tree mismatch")
  }

  @Test
  fun `run uses navigator fallback for non key mapped loop actions`() = runTest {
    val session = FakeDeviceSession(
      containsTextResults = ArrayDeque(listOf(false, true)),
    )
    var generatedActions: List<String>? = null
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _ ->
          FakeTextAgent { userMessage ->
            generatedActions = listOf(userMessage)
            "appId: com.example.app\n---\n- swipe"
          }
        }
      },
      inspectorFactory = { InspectorAgent(treeAgentFactory = { FakeTextAgent { error("unused") } }, evaluateVisualContent = { _, _, _ -> error("unused") }) },
    )

    val journey = Journey(
      name = "loop-fallback",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(
        JourneyStep.Loop(action = "navigate to settings page", until = "Settings", max = 2),
      ),
    )

    val result = orchestrator.run(journey)

    assertThat(result.passed).isTrue()
    assertThat(session.executedFlows).isEqualTo(
      listOf(LAUNCH_FLOW, "appId: com.example.app\n---\n- swipe"),
    )
    assertThat(generatedActions?.single()).isEqualTo("App ID: com.example.app\n\nGenerate a Maestro YAML flow for these actions:\n1. navigate to settings page")
    assertThat(result.segments.single().reasoning).isEqualTo("Text 'Settings' found after 1 iterations")
  }

  @Test
  fun `run loop reports zero iterations when target already visible`() = runTest {
    val session = FakeDeviceSession(
      containsTextResults = ArrayDeque(listOf(true)),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { FakeTextAgent { error("unused") } } },
      inspectorFactory = { InspectorAgent(treeAgentFactory = { FakeTextAgent { error("unused") } }, evaluateVisualContent = { _, _, _ -> error("unused") }) },
    )

    val journey = Journey(
      name = "loop-already-visible",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(
        JourneyStep.Loop(action = "scroll down", until = "Settings", max = 2),
      ),
    )

    val result = orchestrator.run(journey)

    assertThat(result.passed).isTrue()
    assertThat(result.segments.single().reasoning).isEqualTo("Text 'Settings' found after 0 iterations")
    assertThat(session.executedFlows).isEqualTo(listOf(LAUNCH_FLOW))
  }

  @Test
  fun `visible assertion checks device state once`() = runTest {
    val session = FakeDeviceSession(
      containsTextResults = ArrayDeque(listOf(false, true)),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { FakeTextAgent { error("unused") } } },
      inspectorFactory = { InspectorAgent(treeAgentFactory = { FakeTextAgent { error("unused") } }, evaluateVisualContent = { _, _, _ -> error("unused") }) },
    )

    val journey = Journey(
      name = "visible-assertion",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(
        JourneyStep.Assert(description = "Home", mode = AssertMode.VISIBLE),
      ),
    )

    val result = orchestrator.run(journey)

    assertThat(result.passed).isFalse()
    assertThat(result.segments.single().reasoning).isEqualTo("Text 'Home' is not visible")
  }

  @Test
  fun `classifies tap instruction as fast path on mobile`() {
    val actions = listOf("tap Settings")
    val isFastPath = Orchestrator.isFastPath(actions, Platform.ANDROID_MOBILE)
    assertThat(isFastPath).isTrue()
  }

  @Test
  fun `classifies scroll instruction as fast path on mobile`() {
    val actions = listOf("scroll down", "tap OK")
    val isFastPath = Orchestrator.isFastPath(actions, Platform.ANDROID_MOBILE)
    assertThat(isFastPath).isTrue()
  }

  @Test
  fun `fast path taps directly when target is visible`() = runTest {
    val session = FakeDeviceSession(
      containsTextResults = ArrayDeque(listOf(true)), // target is visible
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { FakeTextAgent { error("should not be called") } }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("unused") } },
          evaluateVisualContent = { _, _, _ -> error("unused") },
        )
      },
    )

    val journey = Journey(
      name = "tap-visible",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Action(instruction = "tap Settings")),
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
  }

  @Test
  fun `fast path scrolls to find off-screen target then taps`() = runTest {
    // First containsText: not visible. After scroll: visible.
    val session = FakeDeviceSession(
      containsTextResults = ArrayDeque(listOf(false, true)),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _ ->
          FakeTextAgent { "DOWN" }
        }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("unused") } },
          evaluateVisualContent = { _, _, _ -> error("unused") },
        )
      },
    )

    val journey = Journey(
      name = "scroll-to-find",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Action(instruction = "tap Settings")),
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
    // Should have executed launchApp + scroll flow + tap flow
    assertThat(session.executedFlows.size).isEqualTo(3)
  }

  @Test
  fun `fast path executes non-targeted interaction without tree check`() = runTest {
    val session = FakeDeviceSession()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { FakeTextAgent { error("should not be called") } }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("unused") } },
          evaluateVisualContent = { _, _, _ -> error("unused") },
        )
      },
    )

    val journey = Journey(
      name = "scroll-no-check",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Action(instruction = "scroll down")),
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
    assertThat(session.executedFlows.size).isEqualTo(2) // launchApp + scroll
  }

  @Test
  fun `fast path tap generates correct tapOn Maestro flow`() = runTest {
    val session = FakeDeviceSession(
      containsTextResults = ArrayDeque(listOf(true)),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { FakeTextAgent { error("navigator should not be called on fast path") } }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("unused") } },
          evaluateVisualContent = { _, _, _ -> error("unused") },
        )
      },
    )

    val journey = Journey(
      name = "tap-flow-content",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Action(instruction = "tap Settings")),
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
    assertThat(session.executedFlows).containsExactly(LAUNCH_FLOW, flow("- tapOn: \"Settings\""))
  }

  @Test
  fun `fast path scroll generates correct scroll Maestro flow`() = runTest {
    val session = FakeDeviceSession()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { FakeTextAgent { error("navigator should not be called on fast path") } }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("unused") } },
          evaluateVisualContent = { _, _, _ -> error("unused") },
        )
      },
    )

    val journey = Journey(
      name = "scroll-flow-content",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Action(instruction = "scroll down")),
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
    assertThat(session.executedFlows).containsExactly(LAUNCH_FLOW, flow("- swipe:\n    direction: DOWN"))
  }

  @Test
  fun `mixed mobile gestures all go through fast path without navigator`() = runTest {
    val session = FakeDeviceSession(
      // Two taps need containsText checks: "tap Settings" and "tap OK"
      containsTextResults = ArrayDeque(listOf(true, true)),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { FakeTextAgent { error("navigator should not be called on fast path") } }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("unused") } },
          evaluateVisualContent = { _, _, _ -> error("unused") },
        )
      },
    )

    val journey = Journey(
      name = "mixed-gestures",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(
        JourneyStep.Action(instruction = "tap Settings"),
        JourneyStep.Action(instruction = "scroll down"),
        JourneyStep.Action(instruction = "press back"),
        JourneyStep.Action(instruction = "tap OK"),
      ),
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
    // tap Settings + scroll down + tap OK = 3 flows; press back uses pressKey, not executeFlow
    assertThat(session.executedFlows).containsExactly(
      LAUNCH_FLOW,
      flow("- tapOn: \"Settings\""),
      flow("- swipe:\n    direction: DOWN"),
      flow("- tapOn: \"OK\""),
    )
  }

  @Test
  fun `scroll-to-find triggers when target not visible and generates scroll then tap flows`() = runTest {
    val session = FakeDeviceSession(
      containsTextResults = ArrayDeque(listOf(false, true)),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _ ->
          FakeTextAgent { "DOWN" }
        }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("unused") } },
          evaluateVisualContent = { _, _, _ -> error("unused") },
        )
      },
    )

    val journey = Journey(
      name = "scroll-to-find-content",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Action(instruction = "tap Settings")),
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
    assertThat(session.executedFlows).containsExactly(
      LAUNCH_FLOW,
      flow("- swipe:\n    direction: DOWN"),
      flow("- tapOn: \"Settings\""),
    )
  }

  @Test
  fun `long press goes through fast path and generates longPressOn flow`() = runTest {
    val session = FakeDeviceSession(
      containsTextResults = ArrayDeque(listOf(true)),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { FakeTextAgent { error("navigator should not be called on fast path") } }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("unused") } },
          evaluateVisualContent = { _, _, _ -> error("unused") },
        )
      },
    )

    val journey = Journey(
      name = "long-press",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Action(instruction = "long press Settings")),
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
    assertThat(session.executedFlows).containsExactly(LAUNCH_FLOW, flow("- longPressOn: \"Settings\""))
  }

  @Test
  fun `pull to refresh goes through fast path and generates scroll UP flow`() = runTest {
    val session = FakeDeviceSession()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { FakeTextAgent { error("navigator should not be called on fast path") } }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("unused") } },
          evaluateVisualContent = { _, _, _ -> error("unused") },
        )
      },
    )

    val journey = Journey(
      name = "pull-to-refresh",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Action(instruction = "pull to refresh")),
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
    assertThat(session.executedFlows).containsExactly(LAUNCH_FLOW, flow("- swipe:\n    direction: UP"))
  }

  private companion object {
    const val APP_ID = "com.example.app"
    const val LAUNCH_FLOW = "appId: $APP_ID\n---\n- launchApp"
    fun flow(command: String) = "appId: $APP_ID\n---\n$command"
  }

  private class FakeDeviceSession(
    private val containsTextResults: ArrayDeque<Boolean> = ArrayDeque(),
  ) : DeviceSession {
    override val platform: Platform = Platform.ANDROID_MOBILE
    val executedFlows = mutableListOf<String>()

    override suspend fun executeFlow(yaml: String): FlowResult {
      executedFlows += yaml
      return FlowResult(success = true)
    }
    override suspend fun pressKey(keyName: String) = Unit
    override suspend fun captureHierarchyTree(): HierarchyNode = HierarchyNode(attributes = mapOf("text" to "Home"))
    override suspend fun captureScreenshot(output: Path) = Unit
    override suspend fun shell(command: String): String = ""
    override suspend fun waitForAnimationToEnd() = Unit
    override suspend fun containsText(text: String, ignoreCase: Boolean): Boolean = containsTextResults.removeFirstOrNull() ?: false

    override fun close() = Unit
  }
}
