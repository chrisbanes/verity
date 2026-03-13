package me.chrisbanes.verity.agent

import assertk.assertThat
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
        JourneyStep.Loop(action = "scroll down", until = "Settings", max = 2),
      ),
    )

    val result = orchestrator.run(journey)

    assertThat(result.passed).isTrue()
    assertThat(session.executedFlows).isEqualTo(listOf("appId: com.example.app\n---\n- swipe"))
    assertThat(generatedActions?.single()).isEqualTo("App ID: com.example.app\n\nGenerate a Maestro YAML flow for these actions:\n1. scroll down")
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
