package me.chrisbanes.verity.agent

import ai.koog.prompt.message.Message
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.flow.ActionFlowYamlRenderer
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.InvalidActionFlowException
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.ArtifactErrorKind
import me.chrisbanes.verity.core.result.EvidenceType
import me.chrisbanes.verity.core.result.SegmentExecutionMode
import me.chrisbanes.verity.core.timing.PhaseKind
import me.chrisbanes.verity.core.timing.RecordingPhaseTimer
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
  fun `failed setup launch fails the journey without running segments`() = runTest {
    val session = FakeDeviceSession(launchResult = FlowResult(success = false, output = "launchApp failed: UNKNOWN\n"))
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { error("no segment should run") },
      inspectorFactory = { error("no segment should run") },
    )
    val journey = Journey(
      name = "launch-failure",
      app = "com.example.app",
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(JourneyStep.Action("press d-pad down")),
    )

    val result = orchestrator.run(journey)

    assertThat(result.passed).isFalse()
    assertThat(result.failedAt).isNull()
    assertThat(result.segments).isEmpty()
    assertThat(result.setupError?.kind).isEqualTo(ArtifactErrorKind.SETUP_FAILURE)
    assertThat(result.setupError?.message).isEqualTo("Failed to launch app 'com.example.app': launchApp failed: UNKNOWN")
    assertThat(session.executedActionFlows).containsExactly(ActionFlow("com.example.app", listOf(Interaction.LaunchApp())))
  }

  @Test
  fun `run preserves inspector reasoning for tree assertions`() = runTest {
    val session = FakeDeviceSession()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed": false, "reasoning": "Tree mismatch"}""") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
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
  fun `run records setup and each segment as named timing steps with device time attributed`() = runTest {
    var now = 0L
    val timer = RecordingPhaseTimer { now }
    val fake = FakeDeviceSession(screens = ArrayDeque(listOf(screen("Home"), screen("Home"))))
    val session = object : DeviceSession by fake {
      override suspend fun executeActions(flow: ActionFlow): FlowResult = timer.phase(PhaseKind.DEVICE, "executeActions") {
        now += 5_000_000
        fake.executeActions(flow)
      }

      override suspend fun captureHierarchyTree(): HierarchyNode = timer.phase(PhaseKind.DEVICE, "captureHierarchyTree") {
        now += 7_000_000
        fake.captureHierarchyTree()
      }

      override suspend fun captureHierarchy(filter: HierarchyFilter): String = HierarchyRenderer.render(captureHierarchyTree(), filter)
    }
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed": true, "reasoning": "ok"}""") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
        )
      },
      timer = timer,
    )
    val journey = Journey(
      name = "j",
      app = APP_ID,
      platform = Platform.ANDROID_MOBILE,
      steps = listOf(
        JourneyStep.Assert(description = "Home is visible", mode = AssertMode.TREE),
        JourneyStep.Assert(description = "Home again", mode = AssertMode.TREE),
      ),
    )

    val result = orchestrator.run(journey)

    assertThat(result.passed).isTrue()
    val steps = timer.report().steps
    assertThat(steps.map { it.label }).containsExactly("j setup", "j segment 0", "j segment 1")
    assertThat(steps[0].deviceTotal).isEqualTo(5.milliseconds)
    assertThat(steps[1].deviceTotal).isGreaterThan(Duration.ZERO)
    assertThat(steps[1].phases.map { it.name }).containsExactly("captureHierarchyTree")
    assertThat(steps[2].deviceTotal).isGreaterThan(Duration.ZERO)
  }

  @Test
  fun `run uses navigator fallback for non key mapped loop actions`() = runTest {
    val session = FakeDeviceSession(
      screens = ArrayDeque(listOf(screen("Home"), screen("Settings"))),
    )
    var generatedActions: List<String>? = null
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, userMessage ->
          generatedActions = listOf(userMessage)
          modelReply("""{"actions":[{"type":"defaultScroll"}]}""")
        }
      },
      inspectorFactory = { InspectorAgent(evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"not ready"}""") }, evaluateVisualContent = { _, _, _, _ -> error("unused") }) },
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
    assertThat(session.renderedActionFlows).isEqualTo(
      listOf(LAUNCH_FLOW, flow("- scroll")),
    )
    assertThat(generatedActions?.single()).isEqualTo("App ID: com.example.app\n\nGenerate the structured JSON action list for these instructions:\n1. navigate to settings page")
    assertThat(result.segments.single().reasoning).isEqualTo("Text 'Settings' found after 1 iterations")
    assertThat(session.hierarchyCaptures).isEqualTo(2) // before the body and after the one iteration
  }

  @Test
  fun `loop slow path fallback records generated flow reference`() = runTest {
    val session = FakeDeviceSession(
      screens = ArrayDeque(listOf(screen("Home"), screen("Settings"))),
    )
    val recorder = RecordingArtifactRecorder()
    val generatedYaml = flow("- scroll")
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, _ -> modelReply("""{"actions":[{"type":"defaultScroll"}]}""") }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"not ready"}""") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
        )
      },
      artifactRecorder = recorder,
    )

    val result = orchestrator.run(
      Journey(
        name = "loop-flow-metadata",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Loop(action = "navigate to settings page", until = "Settings", max = 2)),
      ),
    )

    val segment = result.segments.single()
    assertThat(segment.executionMode).isEqualTo(SegmentExecutionMode.LOOP)
    assertThat(segment.actions).containsExactly("navigate to settings page")
    assertThat(segment.generatedFlows).containsExactly("flows/segment-000-loop-000.yaml")
    assertThat(recorder.generatedFlows).containsExactly("0:loop-000:$generatedYaml")
  }

  @Test
  fun `run loop reports zero iterations when target already visible`() = runTest {
    val session = FakeDeviceSession(
      screens = ArrayDeque(listOf(screen("Settings"))),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
      inspectorFactory = { InspectorAgent(evaluateTreeContent = { _, _, _ -> error("unused") }, evaluateVisualContent = { _, _, _, _ -> error("unused") }) },
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
    assertThat(session.renderedActionFlows).isEqualTo(listOf(LAUNCH_FLOW))
    assertThat(session.hierarchyCaptures).isEqualTo(1)
  }

  @Test
  fun `visible assertion checks device state once`() = runTest {
    val session = FakeDeviceSession(
      screens = ArrayDeque(listOf(screen("Other"))),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
      inspectorFactory = { InspectorAgent(evaluateTreeContent = { _, _, _ -> error("unused") }, evaluateVisualContent = { _, _, _, _ -> error("unused") }) },
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
    val segment = result.segments.single()
    assertThat(segment.reasoning).isEqualTo("Text 'Home' is not visible")
    assertThat(segment.error?.kind).isEqualTo(ArtifactErrorKind.JOURNEY_FAILURE)
    assertThat(segment.error?.message).isEqualTo("Text 'Home' is not visible")
    assertThat(session.hierarchyCaptures).isEqualTo(1)
  }

  @Test
  fun `tree assertion records hierarchy evidence`() = runTest {
    val session = FakeDeviceSession()
    val recorder = RecordingArtifactRecorder()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed": true, "reasoning": "Tree matched"}""") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
        )
      },
      artifactRecorder = recorder,
    )

    val result = orchestrator.run(
      Journey(
        name = "tree-evidence",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Assert(description = "Home is visible", mode = AssertMode.TREE)),
      ),
    )

    val segment = result.segments.single()
    assertThat(segment.evidence.single().type).isEqualTo(EvidenceType.HIERARCHY)
    assertThat(segment.evidence.single().path).isEqualTo("evidence/segment-000-tree.txt")
    assertThat(recorder.hierarchies.single()).isEqualTo("[text=Home]\n")
  }

  @Test
  fun `tree assertion sends the prompt rendering without bounds-only wrappers`() = runTest {
    val session = FakeDeviceSession(screens = ArrayDeque(listOf(wrappedHome)))
    val recorder = RecordingArtifactRecorder()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed": true, "reasoning": "Tree matched"}""") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
        )
      },
      artifactRecorder = recorder,
    )

    orchestrator.run(
      Journey(
        name = "tree-prompt",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Assert(description = "Home is visible", mode = AssertMode.TREE)),
      ),
    )

    assertThat(recorder.hierarchies.single()).isEqualTo(HierarchyRenderer.render(wrappedHome, HierarchyFilter.PROMPT))
    assertThat(recorder.hierarchies.single()).isEqualTo("[text=Home, bounds=[10,10][100,50]]\n")
  }

  @Test
  fun `visual assertion records screenshot evidence`() = runTest {
    val screenshotDirectory = Files.createTempDirectory("verity-visual-test")
    try {
      val session = FakeDeviceSession()
      val recorder = RecordingArtifactRecorder(screenshotDirectory)
      val orchestrator = Orchestrator(
        session = session,
        navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
        inspectorFactory = {
          InspectorAgent(
            evaluateTreeContent = { _, _, _ -> error("unused") },
            evaluateVisualContent = { _, _, _, _ -> inspectionReply("""{"passed": true, "reasoning": "Looks right"}""") },
          )
        },
        artifactRecorder = recorder,
      )

      val result = orchestrator.run(
        Journey(
          name = "visual-evidence",
          app = APP_ID,
          platform = Platform.ANDROID_MOBILE,
          steps = listOf(JourneyStep.Assert(description = "Home is visible", mode = AssertMode.VISUAL)),
        ),
      )

      val segment = result.segments.single()
      assertThat(segment.evidence.single().type).isEqualTo(EvidenceType.SCREENSHOT)
      assertThat(segment.evidence.single().path).isEqualTo("evidence/segment-000-visual.png")
      assertThat(recorder.screenshotRequests).containsExactly(0)
      assertThat(session.capturedScreenshotPaths).containsExactly(recorder.screenshotPaths.single())
    } finally {
      deleteRecursively(screenshotDirectory)
    }
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
      screens = ArrayDeque(listOf(screen("Settings"))),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, _ -> modelReply(error("should not be called")) }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
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
    assertThat(session.hierarchyCaptures).isEqualTo(1)
  }

  @Test
  fun `fast path segment records execution mode and source actions`() = runTest {
    val session = FakeDeviceSession(
      screens = ArrayDeque(listOf(screen("Settings"))),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, _ -> modelReply(error("navigator should not be called on fast path")) }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
        )
      },
    )

    val result = orchestrator.run(
      Journey(
        name = "fast-metadata",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Action(instruction = "tap Settings")),
      ),
    )

    val segment = result.segments.single()
    assertThat(segment.executionMode).isEqualTo(SegmentExecutionMode.FAST)
    assertThat(segment.actions).containsExactly("tap Settings")
    assertThat(segment.generatedFlows).isEmpty()
  }

  @Test
  fun `slow path segment records generated flow reference before execution`() = runTest {
    val recorder = RecordingArtifactRecorder()
    var selected: ActionFlow? = null
    val generatedYaml = flow("- tapOn: \"Settings\"")
    val session = FakeDeviceSession(
      onExecuteActions = { yaml ->
        if (yaml == generatedYaml) {
          assertThat(recorder.generatedFlows).containsExactly("0:actions:$generatedYaml")
        }
      },
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused", validateFlow = { selected = it }) { _, _ -> modelReply("""{"actions":[{"type":"tapOnText","text":"Settings"}]}""") }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
        )
      },
      artifactRecorder = recorder,
    )

    val result = orchestrator.run(
      Journey(
        name = "slow-metadata",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Action(instruction = "navigate to settings page")),
      ),
    )

    val segment = result.segments.single()
    assertThat(segment.executionMode).isEqualTo(SegmentExecutionMode.SLOW)
    assertThat(segment.actions).containsExactly("navigate to settings page")
    assertThat(segment.generatedFlows).containsExactly("flows/segment-000-actions.yaml")
    assertThat(recorder.generatedFlows).containsExactly("0:actions:$generatedYaml")
    assertThat(session.renderedActionFlows).containsExactly(LAUNCH_FLOW, generatedYaml)
    assertThat(session.executedActionFlows.last()).isSameInstanceAs(selected)
  }

  private suspend fun runGenerated(vararg replies: String): FakeDeviceSession {
    val session = FakeDeviceSession(screens = ArrayDeque(List(20) { screen("Home") }))
    val queue = ArrayDeque(replies.toList())
    Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(queue.removeFirst()) } },
      inspectorFactory = { InspectorAgent(evaluateTreeContent = { _, _, _ -> error("unused") }, evaluateVisualContent = { _, _, _, _ -> error("unused") }) },
    ).run(
      Journey(
        name = "relaunch",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        // A visible check between actions splits them into separate segments.
        steps = replies.indices.flatMap { listOf(JourneyStep.Action(instruction = "launch the app and open item $it"), JourneyStep.Assert(description = "Home", mode = AssertMode.VISIBLE)) },
      ),
    )
    return session
  }

  @Test
  fun `leading relaunch right after setup is skipped`() = runTest {
    val session = runGenerated(
      """{"actions":[{"type":"launchApp"},{"type":"tapOnText","text":"A"}]}""",
      """{"actions":[{"type":"launchApp"}]}""",
    )
    assertThat(session.executedActionFlows.map { it.actions }).containsExactly(
      listOf(Interaction.LaunchApp()),
      listOf(Interaction.TapOnText("A")),
      listOf(Interaction.LaunchApp()),
    )
  }

  @Test
  fun `relaunch that clears state or runs alone after setup is handled`() = runTest {
    val clearing = runGenerated("""{"actions":[{"type":"launchApp","clearState":true}]}""")
    assertThat(clearing.executedActionFlows.map { it.actions }).containsExactly(listOf(Interaction.LaunchApp()), listOf(Interaction.LaunchApp(clearState = true)))
    val alone = runGenerated("""{"actions":[{"type":"launchApp"}]}""")
    assertThat(alone.executedActionFlows.map { it.actions }).containsExactly(listOf(Interaction.LaunchApp()))
  }

  @Test
  fun `the setup launch stands in for only one generated launch`() = runTest {
    val session = runGenerated("""{"actions":[{"type":"launchApp"}]}""", """{"actions":[{"type":"launchApp"},{"type":"tapOnText","text":"A"}]}""")
    assertThat(session.executedActionFlows.map { it.actions }).containsExactly(listOf(Interaction.LaunchApp()), listOf(Interaction.LaunchApp(), Interaction.TapOnText("A")))
  }

  @Test
  fun `launch only loop body relaunches after its first iteration`() = runTest {
    val session = FakeDeviceSession(screens = ArrayDeque(List(20) { screen("Home") }))
    Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply("""{"actions":[{"type":"launchApp"}]}""") } },
      inspectorFactory = { InspectorAgent(evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"not ready"}""") }, evaluateVisualContent = { _, _, _, _ -> error("unused") }) },
    ).run(
      Journey(
        name = "relaunch-loop",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Loop(action = "relaunch the app", until = "Settings", max = 3)),
      ),
    )
    // Setup, then iterations 2 and 3; the setup launch stands in for iteration 1.
    assertThat(session.executedActionFlows.map { it.actions }).containsExactly(listOf(Interaction.LaunchApp()), listOf(Interaction.LaunchApp()), listOf(Interaction.LaunchApp()))
  }

  /** The fake only supports unbounded capture; the navigator hierarchy uses the bounded overload. */
  private fun withBoundedCapture(fake: FakeDeviceSession): DeviceSession = object : DeviceSession by fake {
    override suspend fun captureHierarchyTree(timeout: kotlin.time.Duration): HierarchyNode = fake.captureHierarchyTree()
  }

  private fun slowPathOrchestrator(session: DeviceSession, messages: MutableList<String>) = Orchestrator(
    session = session,
    navigatorFactory = {
      NavigatorAgent("unused") { _, userMessage ->
        messages += userMessage
        modelReply("""{"actions":[{"type":"tapOnText","text":"Settings"}]}""")
      }
    },
    inspectorFactory = { InspectorAgent(evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"not ready"}""") }, evaluateVisualContent = { _, _, _, _ -> error("unused") }) },
  )

  private val slowJourney = Journey(
    name = "slow-hierarchy",
    app = APP_ID,
    platform = Platform.ANDROID_MOBILE,
    steps = listOf(JourneyStep.Action(instruction = "navigate to settings page")),
  )

  @Test
  fun `slow path passes rendered content hierarchy to navigator`() = runTest {
    val tree = HierarchyNode(attributes = mapOf("text" to "Watch Trailer"), states = setOf("focused"))
    val session = FakeDeviceSession(screens = ArrayDeque(List(5) { tree }))
    val messages = mutableListOf<String>()

    val result = slowPathOrchestrator(withBoundedCapture(session), messages).run(slowJourney)

    assertThat(result.passed).isTrue()
    val message = messages.single()
    assertThat(message).contains("Current accessibility tree (use its focused node to plan navigation):")
    assertThat(message).contains(HierarchyRenderer.render(tree, HierarchyFilter.PROMPT))
    assertThat(session.renderedActionFlows.last()).isEqualTo(flow("- tapOn: \"Settings\""))
  }

  @Test
  fun `slow path continues without hierarchy when capture fails`() = runTest {
    val fake = FakeDeviceSession()
    val session = object : DeviceSession by fake {
      override suspend fun captureHierarchyTree(): HierarchyNode = throw IllegalStateException("boom")
      override suspend fun captureHierarchyTree(timeout: kotlin.time.Duration): HierarchyNode = throw IllegalStateException("boom")
    }
    val messages = mutableListOf<String>()

    val result = slowPathOrchestrator(session, messages).run(slowJourney)

    assertThat(result.passed).isTrue()
    assertThat(messages.single()).doesNotContain("Current accessibility tree")
    assertThat(fake.renderedActionFlows.last()).isEqualTo(flow("- tapOn: \"Settings\""))
  }

  @Test
  fun `slow path continues without hierarchy when bounded capture times out`() = runTest {
    val fake = FakeDeviceSession()
    var first = true
    val session = object : DeviceSession by fake {
      override suspend fun captureHierarchyTree(): HierarchyNode = error("unbounded capture must not be used")
      override suspend fun captureHierarchyTree(timeout: kotlin.time.Duration): HierarchyNode {
        // Only the navigator's capture expires; later bounded captures (focus observation) are unaffected.
        if (first) {
          first = false
          throw me.chrisbanes.verity.device.CaptureDeadlineExceededException(me.chrisbanes.verity.device.CaptureOperation.HIERARCHY)
        }
        return fake.captureHierarchyTree()
      }
    }
    val messages = mutableListOf<String>()

    val result = slowPathOrchestrator(session, messages).run(slowJourney)

    assertThat(result.passed).isTrue()
    assertThat(messages.single()).doesNotContain("Current accessibility tree")
  }

  @Test
  fun `slow path aborts when capture expires because the caller was cancelled`() = runTest {
    val fake = FakeDeviceSession()
    val session = object : DeviceSession by fake {
      override suspend fun captureHierarchyTree(timeout: kotlin.time.Duration): HierarchyNode {
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
        throw me.chrisbanes.verity.device.CaptureDeadlineExceededException(me.chrisbanes.verity.device.CaptureOperation.HIERARCHY)
      }
    }
    val messages = mutableListOf<String>()

    // Run in a child so cancelling it does not cancel the test scope itself.
    val run = async { slowPathOrchestrator(session, messages).run(slowJourney) }
    assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { run.await() }
    assertThat(messages).isEmpty()
  }

  @Test
  fun `slow path aborts when the caller is cancelled while rendering the hierarchy`() = runTest {
    val fake = FakeDeviceSession()
    val session = object : DeviceSession by fake {
      override suspend fun captureHierarchyTree(timeout: kotlin.time.Duration): HierarchyNode {
        // Cancel after capture succeeds: only a render checkpoint can observe it before the navigator call.
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
        return HierarchyNode(attributes = mapOf("text" to "Home"))
      }
    }
    val messages = mutableListOf<String>()

    val run = async { slowPathOrchestrator(session, messages).run(slowJourney) }
    assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { run.await() }
    assertThat(messages).isEmpty()
  }

  @Test
  fun `slow path rethrows cancellation from hierarchy capture before calling navigator`() = runTest {
    val fake = FakeDeviceSession()
    val session = object : DeviceSession by fake {
      override suspend fun captureHierarchyTree(timeout: kotlin.time.Duration): HierarchyNode = throw kotlin.coroutines.cancellation.CancellationException("cancelled")
    }
    val messages = mutableListOf<String>()

    assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { slowPathOrchestrator(session, messages).run(slowJourney) }
    assertThat(messages).isEmpty()
  }

  @Test
  fun `slow path omits bounds-only wrappers from the navigator hierarchy`() = runTest {
    val session = FakeDeviceSession(screens = ArrayDeque(List(5) { wrappedHome }))
    val messages = mutableListOf<String>()

    slowPathOrchestrator(withBoundedCapture(session), messages).run(slowJourney)

    assertThat(messages.single()).contains("[text=Home, bounds=[10,10][100,50]]")
    assertThat(messages.single()).doesNotContain("[bounds=[0,0][1000,500]]")
  }

  @Test
  fun `loop slow path passes hierarchy to navigator`() = runTest {
    val tree = HierarchyNode(attributes = mapOf("text" to "Home"), states = setOf("focused"))
    val session = FakeDeviceSession(screens = ArrayDeque(listOf(screen("Home"), tree, screen("Settings"))))
    val messages = mutableListOf<String>()
    val journey = slowJourney.copy(steps = listOf(JourneyStep.Loop(action = "navigate to settings page", until = "Settings", max = 1)))

    slowPathOrchestrator(withBoundedCapture(session), messages).run(journey)

    assertThat(messages.single()).contains(HierarchyRenderer.render(tree, HierarchyFilter.PROMPT))
  }

  @Test
  fun `slow path recorder failure does not prevent flow execution`() = runTest {
    val generatedYaml = flow("- tapOn: \"Settings\"")
    val session = FakeDeviceSession()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply("""{"actions":[{"type":"tapOnText","text":"Settings"}]}""") } },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
        )
      },
      artifactRecorder = ThrowingArtifactRecorder(),
    )

    val result = orchestrator.run(
      Journey(
        name = "slow-recorder-failure",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Action(instruction = "navigate to settings page")),
      ),
    )

    val segment = result.segments.single()
    assertThat(segment.passed).isTrue()
    assertThat(segment.generatedFlows).isEmpty()
    assertThat(session.renderedActionFlows).containsExactly(LAUNCH_FLOW, generatedYaml)
  }

  @Test
  fun `tree recorder failure does not fail passing assertion`() = runTest {
    val orchestrator = Orchestrator(
      session = FakeDeviceSession(),
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed": true, "reasoning": "Tree matched"}""") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
        )
      },
      artifactRecorder = ThrowingArtifactRecorder(),
    )

    val result = orchestrator.run(
      Journey(
        name = "tree-recorder-failure",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Assert(description = "Home is visible", mode = AssertMode.TREE)),
      ),
    )

    val segment = result.segments.single()
    assertThat(segment.passed).isTrue()
    assertThat(segment.evidence).isEmpty()
  }

  @Test
  fun `visual recorder failure does not fail passing assertion`() = runTest {
    val session = FakeDeviceSession()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> inspectionReply("""{"passed": true, "reasoning": "Looks right"}""") },
        )
      },
      artifactRecorder = ThrowingArtifactRecorder(),
    )

    val result = orchestrator.run(
      Journey(
        name = "visual-recorder-failure",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Assert(description = "Home is visible", mode = AssertMode.VISUAL)),
      ),
    )

    val segment = result.segments.single()
    assertThat(segment.passed).isTrue()
    assertThat(segment.evidence).isEmpty()
    assertThat(session.capturedScreenshotPaths.size).isEqualTo(1)
  }

  @Test
  fun `visual artifact capture IOException falls back to temp screenshot without evidence`() = runTest {
    val artifactPath = Files.createTempDirectory("verity-unwritable-artifact").resolve("artifact.png")
    val captureAttempts = mutableListOf<Path>()
    val session = FakeDeviceSession(
      onCaptureScreenshot = { path ->
        captureAttempts.add(path)
        if (path == artifactPath) throw IOException("artifact path unavailable")
      },
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, screenshotPath, _ ->
            assertThat(screenshotPath).isNotEqualTo(artifactPath)
            inspectionReply("""{"passed": true, "reasoning": "Looks right"}""")
          },
        )
      },
      artifactRecorder = StaticScreenshotArtifactRecorder(artifactPath),
    )

    val result = orchestrator.run(
      Journey(
        name = "visual-artifact-capture-failure",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Assert(description = "Home is visible", mode = AssertMode.VISUAL)),
      ),
    )

    val segment = result.segments.single()
    assertThat(segment.passed).isTrue()
    assertThat(segment.evidence).isEmpty()
    assertThat(captureAttempts.size).isEqualTo(2)
    assertThat(captureAttempts.first()).isEqualTo(artifactPath)
    assertThat(session.capturedScreenshotPaths).containsExactly(captureAttempts.last())
  }

  @Test
  fun `visual artifact capture device failure is not retried with temp screenshot`() = runTest {
    val artifactPath = Files.createTempDirectory("verity-visual-device-failure").resolve("artifact.png")
    val captureAttempts = mutableListOf<Path>()
    val orchestrator = Orchestrator(
      session = FakeDeviceSession(
        onCaptureScreenshot = { path ->
          captureAttempts.add(path)
          if (path == artifactPath) error("device screenshot failed")
        },
      ),
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("visual evaluator should not run") },
        )
      },
      artifactRecorder = StaticScreenshotArtifactRecorder(artifactPath),
    )

    assertFailsWith<IllegalStateException> {
      orchestrator.run(
        Journey(
          name = "visual-device-failure",
          app = APP_ID,
          platform = Platform.ANDROID_MOBILE,
          steps = listOf(JourneyStep.Assert(description = "Home is visible", mode = AssertMode.VISUAL)),
        ),
      )
    }

    assertThat(captureAttempts).containsExactly(artifactPath)
  }

  @Test
  fun `visual evaluator failure after artifact capture is not retried with temp screenshot`() = runTest {
    val artifactPath = Files.createTempDirectory("verity-visual-evaluator-failure").resolve("artifact.png")
    val evaluatedPaths = mutableListOf<Path>()
    val orchestrator = Orchestrator(
      session = FakeDeviceSession(),
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(error("unused")) } },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, screenshotPath, _ ->
            evaluatedPaths.add(screenshotPath)
            error("visual evaluator failed")
          },
        )
      },
      artifactRecorder = StaticScreenshotArtifactRecorder(artifactPath),
    )

    assertFailsWith<ModelFailureException> {
      orchestrator.run(
        Journey(
          name = "visual-evaluator-failure",
          app = APP_ID,
          platform = Platform.ANDROID_MOBILE,
          steps = listOf(JourneyStep.Assert(description = "Home is visible", mode = AssertMode.VISUAL)),
        ),
      )
    }

    assertThat(evaluatedPaths).containsExactly(artifactPath)
  }

  @Test
  fun `fast path scrolls to find off-screen target then taps`() = runTest {
    // First screen lacks the target; the post-scroll screen has it.
    val session = FakeDeviceSession(
      screens = ArrayDeque(listOf(screen("Home"), screen("Settings"))),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, _ -> modelReply("DOWN") }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
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
    assertThat(session.renderedActionFlows.size).isEqualTo(3)
    assertThat(session.hierarchyCaptures).isEqualTo(2) // initial + one post-scroll
  }

  @Test
  fun `scroll-to-find captures once per attempt and prompts with the carried tree`() = runTest {
    val screens = (0..5).map { screen("Other $it") }
    val session = FakeDeviceSession(screens = ArrayDeque(screens))
    val prompts = mutableListOf<String>()
    val orchestrator = scrollOrchestrator(session) {
      prompts += it
      modelReply("DOWN")
    }

    val result = orchestrator.run(tapSettingsJourney())

    assertThat(result.passed).isTrue()
    // The tap is still attempted after the five-attempt cap.
    assertThat(session.renderedActionFlows).containsExactly(
      LAUNCH_FLOW,
      *Array(5) { flow("- swipe:\n    direction: DOWN") },
      flow("- tapOn: \"Settings\""),
    )
    assertThat(session.hierarchyCaptures).isEqualTo(6) // 1 initial + 5 post-scroll
    assertThat(prompts.map { it.substringAfter("Current screen:\n") })
      .isEqualTo(screens.take(5).map { HierarchyRenderer.render(it, HierarchyFilter.PROMPT) })
  }

  @Test
  fun `scroll-to-find without a suggested direction does not scroll or recapture`() = runTest {
    val session = FakeDeviceSession(screens = ArrayDeque(listOf(screen("Home"), screen("Settings"))))
    val replies = ArrayDeque(listOf("NONE", "DOWN"))
    val prompts = mutableListOf<String>()
    val orchestrator = scrollOrchestrator(session) {
      prompts += it
      modelReply(replies.removeFirst())
    }

    val result = orchestrator.run(tapSettingsJourney())

    assertThat(result.passed).isTrue()
    assertThat(session.renderedActionFlows).containsExactly(
      LAUNCH_FLOW,
      flow("- swipe:\n    direction: DOWN"),
      flow("- tapOn: \"Settings\""),
    )
    assertThat(session.hierarchyCaptures).isEqualTo(2) // initial + the one performed scroll
    val home = HierarchyRenderer.render(screen("Home"), HierarchyFilter.PROMPT)
    assertThat(prompts.map { it.substringAfter("Current screen:\n") }).containsExactly(home, home)
  }

  @Test
  fun `scroll-to-find prompts with the prompt rendering without bounds-only wrappers`() = runTest {
    val session = FakeDeviceSession(screens = ArrayDeque(listOf(wrappedHome, screen("Settings"))))
    val prompts = mutableListOf<String>()
    val orchestrator = scrollOrchestrator(session) {
      prompts += it
      modelReply("DOWN")
    }

    orchestrator.run(tapSettingsJourney())

    assertThat(prompts.single().substringAfter("Current screen:\n")).isEqualTo("[text=Home, bounds=[10,10][100,50]]\n")
  }

  @Test
  fun `scroll-to-find that never gets a direction still honours the attempt cap then taps`() = runTest {
    val session = FakeDeviceSession(screens = ArrayDeque(listOf(screen("Home"))))
    var navigatorCalls = 0
    val orchestrator = scrollOrchestrator(session) {
      navigatorCalls++
      modelReply("NONE")
    }

    val result = orchestrator.run(tapSettingsJourney())

    assertThat(result.passed).isTrue()
    assertThat(navigatorCalls).isEqualTo(5)
    assertThat(session.hierarchyCaptures).isEqualTo(1) // nothing scrolled, so nothing to recapture
    assertThat(session.renderedActionFlows).containsExactly(LAUNCH_FLOW, flow("- tapOn: \"Settings\""))
  }

  private fun tapSettingsJourney() = Journey(
    name = "scroll-to-find",
    app = APP_ID,
    platform = Platform.ANDROID_MOBILE,
    steps = listOf(JourneyStep.Action(instruction = "tap Settings")),
  )

  private fun scrollOrchestrator(session: DeviceSession, navigatorReply: (userMessage: String) -> Message.Assistant) = Orchestrator(
    session = session,
    navigatorFactory = { NavigatorAgent("unused") { _, userMessage -> navigatorReply(userMessage) } },
    inspectorFactory = { InspectorAgent(evaluateTreeContent = { _, _, _ -> error("unused") }, evaluateVisualContent = { _, _, _, _ -> error("unused") }) },
  )

  @Test
  fun `fast path executes non-targeted interaction without tree check`() = runTest {
    val session = FakeDeviceSession()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, _ -> modelReply(error("should not be called")) }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
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
    assertThat(session.renderedActionFlows.size).isEqualTo(2) // launchApp + scroll
  }

  @Test
  fun `fast path tap generates correct tapOn Maestro flow`() = runTest {
    val session = FakeDeviceSession(
      screens = ArrayDeque(listOf(screen("Settings"))),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, _ -> modelReply(error("navigator should not be called on fast path")) }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
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
    assertThat(session.renderedActionFlows).containsExactly(LAUNCH_FLOW, flow("- tapOn: \"Settings\""))
  }

  @Test
  fun `fast path scroll generates correct scroll Maestro flow`() = runTest {
    val session = FakeDeviceSession()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, _ -> modelReply(error("navigator should not be called on fast path")) }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
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
    assertThat(session.renderedActionFlows).containsExactly(LAUNCH_FLOW, flow("- swipe:\n    direction: DOWN"))
  }

  @Test
  fun `mixed mobile gestures all go through fast path without navigator`() = runTest {
    val session = FakeDeviceSession(
      // Each tap checks its own screen: "tap Settings" and "tap OK"
      screens = ArrayDeque(listOf(screen("Settings"), screen("OK"))),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, _ -> modelReply(error("navigator should not be called on fast path")) }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
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
    assertThat(session.renderedActionFlows).containsExactly(
      LAUNCH_FLOW,
      flow("- tapOn: \"Settings\""),
      flow("- swipe:\n    direction: DOWN"),
      flow("- tapOn: \"OK\""),
    )
  }

  @Test
  fun `scroll-to-find triggers when target not visible and generates scroll then tap flows`() = runTest {
    val session = FakeDeviceSession(
      screens = ArrayDeque(listOf(screen("Home"), screen("Settings"))),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, _ -> modelReply("DOWN") }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
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
    assertThat(session.renderedActionFlows).containsExactly(
      LAUNCH_FLOW,
      flow("- swipe:\n    direction: DOWN"),
      flow("- tapOn: \"Settings\""),
    )
  }

  @Test
  fun `long press goes through fast path and generates longPressOn flow`() = runTest {
    val session = FakeDeviceSession(
      screens = ArrayDeque(listOf(screen("Settings"))),
    )
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, _ -> modelReply(error("navigator should not be called on fast path")) }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
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
    assertThat(session.renderedActionFlows).containsExactly(LAUNCH_FLOW, flow("- longPressOn: \"Settings\""))
  }

  @Test
  fun `pull to refresh goes through fast path and generates scroll UP flow`() = runTest {
    val session = FakeDeviceSession()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _, _ -> modelReply(error("navigator should not be called on fast path")) }
      },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("unused") },
          evaluateVisualContent = { _, _, _, _ -> error("unused") },
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
    assertThat(session.renderedActionFlows).containsExactly(LAUNCH_FLOW, flow("- swipe:\n    direction: UP"))
  }

  @Test
  fun `loop checks immediately and only after complete ordered bodies including final body`() = runTest {
    val events = mutableListOf<String>()
    val session = LoopSession(events, Platform.ANDROID_TV)
    var checks = 0
    val orchestrator = loopOrchestrator(
      session,
      inspector = InspectorAgent(
        evaluateTreeContent = { _, _, _ ->
          events += "check"
          checks++
          inspectionReply("""{"passed":${checks == 2},"reasoning":"condition $checks"}""")
        },
        evaluateVisualContent = { _, _, _, _ -> error("unused") },
      ),
    )
    val result = orchestrator.run(loopJourney("Press D-pad down; press D-pad right", 1, Platform.ANDROID_TV))
    val segment = result.segments.single()
    assertThat(result.passed).isTrue()
    assertThat(events).containsExactly("check", "Remote Dpad Down", "Remote Dpad Right", "check")
    assertThat(segment.actions).containsExactly("Press D-pad down", "press D-pad right")
    assertThat(segment.loop?.iterations).isEqualTo(1)
    assertThat(segment.loop?.tier).isEqualTo(me.chrisbanes.verity.core.result.ConditionTier.TREE)
    assertThat(segment.loop?.reasoning).isEqualTo("condition 2")
  }

  @Test
  fun `zero permitted bodies still check once and retain positive or negative condition metadata`() = runTest {
    for (passed in listOf(true, false)) {
      val events = mutableListOf<String>()
      val session = LoopSession(events, Platform.ANDROID_TV)
      val inspector = InspectorAgent(
        evaluateTreeContent = { _, _, _ ->
          events += "check"
          inspectionReply("""{"passed":$passed,"reasoning":"immediate"}""")
        },
        evaluateVisualContent = { _, _, _, _ -> error("unused") },
      )
      val result = loopOrchestrator(session, inspector).run(loopJourney("Press down", 0, Platform.ANDROID_TV))
      assertThat(events).containsExactly("check")
      assertThat(result.passed).isEqualTo(passed)
      assertThat(result.segments.single().loop?.iterations).isEqualTo(0)
      assertThat(result.segments.single().loop?.reasoning).isEqualTo("immediate")
    }
  }

  @Test
  fun `mixed loop body generates the complete ordered body once without a mapped prefix`() = runTest {
    val events = mutableListOf<String>()
    val session = LoopSession(events, Platform.ANDROID_TV)
    val messages = mutableListOf<String>()
    var checks = 0
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, _, _ ->
        checks++
        inspectionReply("""{"passed":${checks == 2},"reasoning":"ready"}""")
      },
      evaluateVisualContent = { _, _, _, _ -> error("unused") },
    )
    val navigator = NavigatorAgent("unused") { _, message ->
      messages += message
      modelReply("""{"actions":[{"type":"defaultScroll"}]}""")
    }
    val recorder = RecordingArtifactRecorder()
    val result = loopOrchestrator(session, inspector, navigator, recorder).run(loopJourney("Press D-pad down; navigate to settings page", 1, Platform.ANDROID_TV))
    assertThat(messages).containsExactly("App ID: $APP_ID\n\nGenerate the structured JSON action list for these instructions:\n1. Press D-pad down\n2. navigate to settings page")
    assertThat(events).containsExactly("flow")
    assertThat(result.segments.single().loop?.iterations).isEqualTo(1)
    assertThat(result.segments.single().generatedFlows).containsExactly("flows/segment-000-loop-000.yaml")
  }

  @Test
  fun `failed first or second mapped body command stops without count or another condition check`() = runTest {
    for (failedCommand in listOf(1, 2)) {
      val events = mutableListOf<String>()
      val session = LoopSession(events, Platform.ANDROID_MOBILE, failAt = failedCommand)
      val inspector = InspectorAgent(
        evaluateTreeContent = { _, _, _ ->
          events += "check"
          inspectionReply("""{"passed":false,"reasoning":"previous condition"}""")
        },
        evaluateVisualContent = { _, _, _, _ -> error("unused") },
      )
      val result = loopOrchestrator(session, inspector).run(loopJourney("Scroll down; swipe left; scroll up", 3, Platform.ANDROID_MOBILE))
      val segment = result.segments.single()
      assertThat(events).isEqualTo(listOf("check") + List(failedCommand) { "flow" })
      assertThat(result.passed).isFalse()
      assertThat(segment.loop?.iterations).isEqualTo(0)
      assertThat(segment.loop?.reasoning).isEqualTo("previous condition")
      assertThat(segment.error?.kind).isEqualTo(ArtifactErrorKind.JOURNEY_FAILURE)
      assertThat(segment.reasoning).isEqualTo("Flow execution failed: command failed")
    }
  }

  @Test
  fun `max exhaustion counts complete bodies and retains final condition reasoning and evidence`() = runTest {
    val events = mutableListOf<String>()
    val session = LoopSession(events, Platform.ANDROID_TV)
    var checks = 0
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, _, _ ->
        checks++
        events += "check"
        inspectionReply("""{"passed":false,"reasoning":"negative $checks"}""")
      },
      evaluateVisualContent = { _, _, _, _ -> error("unused") },
    )
    val result = loopOrchestrator(session, inspector, recorder = RecordingArtifactRecorder()).run(loopJourney("Press D-pad down", 2, Platform.ANDROID_TV))
    val segment = result.segments.single()
    assertThat(events).containsExactly("check", "Remote Dpad Down", "check", "Remote Dpad Down", "check")
    assertThat(segment.loop?.iterations).isEqualTo(2)
    assertThat(segment.loop?.reasoning).isEqualTo("negative 3")
    assertThat(segment.error?.kind).isEqualTo(ArtifactErrorKind.JOURNEY_FAILURE)
    assertThat(segment.evidence.single().type).isEqualTo(EvidenceType.HIERARCHY)
  }

  @Test
  fun `failed automatic scroll interrupts loop without tapping or rechecking`() = runTest {
    val events = mutableListOf<String>()
    val session = LoopSession(events, Platform.ANDROID_MOBILE, failAt = 1)
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, _, _ ->
        events += "check"
        inspectionReply("""{"passed":false,"reasoning":"previous"}""")
      },
      evaluateVisualContent = { _, _, _, _ -> error("unused") },
    )
    val navigator = NavigatorAgent("unused") { _, _ -> modelReply("DOWN") }
    val result = loopOrchestrator(session, inspector, navigator).run(loopJourney("Tap Settings; swipe left", 2, Platform.ANDROID_MOBILE))
    assertThat(events).containsExactly("check", "flow")
    assertThat(result.segments.single().loop?.iterations).isEqualTo(0)
    assertThat(result.segments.single().loop?.reasoning).isEqualTo("previous")
    assertThat(result.segments.single().reasoning).isEqualTo("Flow execution failed: command failed")
  }

  @Test
  fun `cancellation during body and model failure during check stop remaining work immediately`() = runTest {
    for (cancelBody in listOf(true, false)) {
      val events = mutableListOf<String>()
      val session = LoopSession(events, Platform.ANDROID_TV, onPress = {
        if (cancelBody) throw kotlin.coroutines.cancellation.CancellationException("body cancelled")
      })
      var checks = 0
      val inspector = InspectorAgent(
        evaluateTreeContent = { _, _, _ ->
          events += "check"
          checks++
          if (checks == 2) error("request failure")
          inspectionReply("""{"passed":false,"reasoning":"previous"}""")
        },
        evaluateVisualContent = { _, _, _, _ -> error("unused") },
      )
      val journey = loopJourney("Press D-pad down; press D-pad right", 2, Platform.ANDROID_TV).copy(
        steps = listOf(JourneyStep.Loop("Press D-pad down; press D-pad right", "page is ready", 2), JourneyStep.Action("Press back")),
      )
      if (cancelBody) {
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { loopOrchestrator(session, inspector).run(journey) }
        assertThat(events).containsExactly("check", "Remote Dpad Down")
      } else {
        assertFailsWith<ModelFailureException> { loopOrchestrator(session, inspector).run(journey) }
        assertThat(events).containsExactly("check", "Remote Dpad Down", "Remote Dpad Right", "check")
      }
    }
  }

  @Test fun `scroll model failure stops action and loop before any fallback or later work`() = runTest {
    for (loop in listOf(false, true)) {
      val events = mutableListOf<String>()
      val session = LoopSession(events, Platform.ANDROID_MOBILE)
      val inspector = InspectorAgent(evaluateTreeContent = { _, _, _ ->
        events += "check"
        inspectionReply("""{"passed":false,"reasoning":"previous"}""")
      }, evaluateVisualContent = { _, _, _, _ -> error("unused") })
      val navigator = NavigatorAgent("context") { _, _ -> error("HTTP_BODY_SENTINEL sk-secret") }
      val journey = loopJourney("Tap Settings; swipe left", 2, Platform.ANDROID_MOBILE).copy(steps = listOf(if (loop) JourneyStep.Loop("Tap Settings; swipe left", "page is ready", 2) else JourneyStep.Action("Tap Settings"), JourneyStep.Action("Press back")))
      assertFailsWith<ModelFailureException> { loopOrchestrator(session, inspector, navigator).run(journey) }
      assertThat(events).containsExactly(*(if (loop) arrayOf("check") else emptyArray()))
    }
  }

  @Test fun `invalid truncated empty timed out and failed slow generation never record or execute flow bodies`() = runTest {
    for (loop in listOf(false, true)) {
      for (type in listOf("invalid", "truncated", "empty", "timeout", "request")) {
        val events = mutableListOf<String>()
        val session = LoopSession(events, Platform.ANDROID_TV)
        val recorder = RecordingArtifactRecorder()
        val inspector = InspectorAgent(evaluateTreeContent = { _, _, _ ->
          events += "check"
          inspectionReply("""{"passed":false,"reasoning":"previous"}""")
        }, evaluateVisualContent = { _, _, _, _ -> error("unused") })
        val navigator = NavigatorAgent("context") { _, _ ->
          when (type) {
            "invalid" -> modelReply("appId: com.example\n---\n- tapOn:")

            "truncated" -> modelReply("appId: com.example\n---\n- launchApp", "max_tokens")

            "empty" -> modelReply(" ")

            "timeout" -> {
              kotlinx.coroutines.delay(30_001)
              modelReply("unused")
            }

            else -> error("HTTP_BODY_SENTINEL sk-secret")
          }
        }
        val journey = loopJourney("Press D-pad down; complete onboarding wizard", 2, Platform.ANDROID_TV).copy(steps = listOf(if (loop) JourneyStep.Loop("Press D-pad down; complete onboarding wizard", "page is ready", 2) else JourneyStep.Action("complete onboarding wizard"), JourneyStep.Action("Press back")))
        assertFailsWith<ModelFailureException> { loopOrchestrator(session, inspector, navigator, recorder).run(journey) }
        assertThat(recorder.generatedFlows).isEmpty()
        assertThat(events).containsExactly(*(if (loop) arrayOf("check") else emptyArray()))
      }
    }
  }

  @Test fun `valid scroll NONE remains ordinary navigation with fallback tap`() = runTest {
    val events = mutableListOf<String>()
    val session = LoopSession(events, Platform.ANDROID_MOBILE)
    val navigator = NavigatorAgent("context") { _, _ -> modelReply("NONE") }
    val inspector = InspectorAgent(evaluateTreeContent = { _, _, _ -> error("unused") }, evaluateVisualContent = { _, _, _, _ -> error("unused") })
    val result = loopOrchestrator(session, inspector, navigator).run(Journey("none", APP_ID, Platform.ANDROID_MOBILE, listOf(JourneyStep.Action("Tap Settings"))))
    assertThat(result.passed).isTrue()
    assertThat(events).containsExactly("flow")
  }

  @Test fun `invalid final mapped action prevents direct key prefix in actions and loops`() = runTest {
    for (loop in listOf(false, true)) {
      val events = mutableListOf<String>()
      val session = LoopSession(events, Platform.ANDROID_MOBILE)
      val inspector = InspectorAgent(evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"continue"}""") }, evaluateVisualContent = { _, _, _, _ -> error("unused") })
      val steps = if (loop) listOf(JourneyStep.Loop("Press back; Tap [", "ready", 1)) else listOf(JourneyStep.Action("Press back"), JourneyStep.Action("Tap ["))
      assertFailsWith<InvalidActionFlowException> { loopOrchestrator(session, inspector).run(Journey("invalid", APP_ID, Platform.ANDROID_MOBILE, steps)) }
      assertThat(events).isEmpty()
      assertThat(session.executedActionFlows).isEqualTo(listOf(ActionFlow(APP_ID, listOf(Interaction.LaunchApp()))))
    }
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun `waits poll without navigation and success continues later actions`() = runTest {
    for (delayed in listOf(false, true)) {
      val actions = FakeDeviceSession()
      var captures = 0
      val session = object : DeviceSession by actions {
        override suspend fun captureHierarchyTree(timeout: kotlin.time.Duration): HierarchyNode {
          captures++
          return HierarchyNode(mapOf("text" to if (!delayed || captures == 2) "Ready" else "Other"))
        }
      }
      val orchestrator = Orchestrator(
        session,
        { NavigatorAgent("unused") { _, _ -> error("wait must not navigate") } },
        { InspectorAgent({ _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"not ready"}""") }, { _, _, _, _ -> error("visual") }) },
        nowNanos = { testScheduler.currentTime * 1_000_000 },
      )
      val result = orchestrator.run(Journey("wait", APP_ID, Platform.ANDROID_MOBILE, listOf(JourneyStep.Wait("Ready", 3), JourneyStep.Action("Scroll down"))))
      val wait = result.segments.first()
      assertThat(result.passed).isTrue()
      assertThat(wait.executionMode).isEqualTo(SegmentExecutionMode.WAIT)
      assertThat(wait.actions).isEmpty()
      assertThat(wait.generatedFlows).isEmpty()
      assertThat(wait.wait?.condition).isEqualTo("Ready")
      assertThat(wait.wait?.timeoutSeconds).isEqualTo(3)
      assertThat(wait.wait?.checks).isEqualTo(if (delayed) 2 else 1)
      assertThat(wait.wait?.elapsedMs).isEqualTo(if (delayed) 1000L else 0L)
      assertThat(actions.executedActionFlows.size).isEqualTo(2)
      assertThat(actions.executedActionFlows.first().actions).containsExactly(Interaction.LaunchApp())
    }
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun `wait timeout stops later actions retaining only completed reasoning and evidence`() = runTest {
    val actions = FakeDeviceSession()
    var captures = 0
    val session = object : DeviceSession by actions {
      override suspend fun captureHierarchyTree(timeout: kotlin.time.Duration): HierarchyNode {
        if (++captures == 2) kotlinx.coroutines.awaitCancellation()
        return HierarchyNode(mapOf("text" to "Other"))
      }
    }
    val orchestrator = Orchestrator(
      session,
      { NavigatorAgent("unused") { _, _ -> error("navigate") } },
      { InspectorAgent({ _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"still loading"}""") }, { _, _, _, _ -> error("visual") }) },
      artifactRecorder = RecordingArtifactRecorder(),
      nowNanos = { testScheduler.currentTime * 1_000_000 },
    )
    val result = orchestrator.run(Journey("wait", APP_ID, Platform.ANDROID_MOBILE, listOf(JourneyStep.Wait("Ready", 3), JourneyStep.Action("Press back"))))
    val segment = result.segments.single()
    assertThat(result.passed).isFalse()
    assertThat(segment.reasoning).isEqualTo("Wait timed out after 3 seconds: Ready")
    assertThat(segment.wait?.checks).isEqualTo(1)
    assertThat(segment.wait?.elapsedMs).isEqualTo(3000L)
    assertThat(segment.wait?.tier).isEqualTo(me.chrisbanes.verity.core.result.ConditionTier.TREE)
    assertThat(segment.wait?.reasoning).isEqualTo("still loading")
    assertThat(segment.evidence.single().type).isEqualTo(EvidenceType.HIERARCHY)
    assertThat(actions.executedActionFlows.size).isEqualTo(1)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun `wait expiry before first check has nullable tier and cancellations have no completed result`() = runTest {
    for (failure in listOf("expiry", "foreign", "model", "parent")) {
      val actions = FakeDeviceSession()
      val session = object : DeviceSession by actions {
        override suspend fun captureHierarchyTree(timeout: kotlin.time.Duration): HierarchyNode {
          if (failure == "foreign") throw kotlin.coroutines.cancellation.CancellationException("foreign wait")
          if (failure != "model") kotlinx.coroutines.awaitCancellation()
          return HierarchyNode(mapOf("text" to "Other"))
        }
      }
      val orchestrator = Orchestrator(
        session,
        { NavigatorAgent("unused") { _, _ -> error("navigate") } },
        { InspectorAgent({ _, _, _ -> error("model unavailable") }, { _, _, _, _ -> error("visual") }) },
        nowNanos = { testScheduler.currentTime * 1_000_000 },
      )
      val journey = Journey("wait", APP_ID, Platform.ANDROID_MOBILE, listOf(JourneyStep.Wait("Ready", 3), JourneyStep.Action("Press back")))
      when (failure) {
        "expiry" -> {
          val segment = orchestrator.run(journey).segments.single()
          assertThat(segment.wait?.checks).isEqualTo(0)
          assertThat(segment.wait?.tier).isNull()
          assertThat(segment.evidence).isEmpty()
        }

        "foreign" -> assertThat(assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { orchestrator.run(journey) }.message).isEqualTo("foreign wait")

        "model" -> assertFailsWith<ModelFailureException> { orchestrator.run(journey) }

        else -> assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { kotlinx.coroutines.withTimeout(100) { orchestrator.run(journey) } }
      }
      assertThat(actions.executedActionFlows.size).isEqualTo(1)
    }
  }

  /** An Android TV fake whose captures follow [afterPress] once Select is pressed, logging each capture as an event. */
  private fun selectSession(before: HierarchyNode, afterPress: List<HierarchyNode>): me.chrisbanes.verity.device.FakeDeviceSession {
    lateinit var fake: me.chrisbanes.verity.device.FakeDeviceSession
    var afterCaptures = 0
    fake = me.chrisbanes.verity.device.FakeDeviceSession(Platform.ANDROID_TV, hierarchyProvider = {
      fake.events += "capture"
      if (fake.pressedKeys.isEmpty()) before else afterPress[minOf(afterCaptures++, afterPress.lastIndex)]
    })
    return fake
  }

  private fun text(value: String) = HierarchyNode(attributes = mapOf("text" to value))

  @Test
  fun `side effect loop checks the tree its key press settled on`() = runTest {
    val session = selectSession(text("Item 0"), listOf(text("Item 1"), text("Item 2"), text("Item done")))
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"not yet"}""") },
      evaluateVisualContent = { _, _, _, _ -> error("unexpected visual") },
    )
    val journey = Journey("settle", APP_ID, Platform.ANDROID_TV, steps = listOf(JourneyStep.Loop("Press select", "Item done", 3)))

    val result = loopOrchestrator(session, inspector).run(journey)

    assertThat(result.passed).isTrue()
    assertThat(result.segments.single().loop?.iterations).isEqualTo(1)
    assertThat(session.pressedKeys).containsExactly("Remote Dpad Center")
    // Four settle captures (T1, T2, Tdone, Tdone) and the trail's after-capture; the check captures nothing.
    val afterPress = session.events.dropWhile { it != "name:Remote Dpad Center" }.drop(1)
    assertThat(afterPress).isEqualTo(List(5) { "capture" })
  }

  @Test
  fun `visual loop keeps the animation wait after each key press`() = runTest {
    val fake = selectSession(text("Item 0"), listOf(text("Item 1")))
    val session = object : DeviceSession by fake {
      override suspend fun captureScreenshot(output: Path) {
        Files.write(output, byteArrayOf(1))
      }
    }
    val inspector = InspectorAgent(
      evaluateTreeContent = { _, _, _ -> error("unexpected tree") },
      evaluateVisualContent = { _, _, _, _ -> inspectionReply("""{"passed":false,"reasoning":"not yet"}""") },
    )
    val journey = Journey("visual", APP_ID, Platform.ANDROID_TV, steps = listOf(JourneyStep.Loop("Press select", "visually the item is done", 2)))

    loopOrchestrator(session, inspector).run(journey)

    val presses = fake.events.withIndex().filter { it.value == "name:Remote Dpad Center" }
    assertThat(presses.size).isEqualTo(2)
    assertThat(presses.map { fake.events[it.index + 1] }).containsExactly("settle", "settle")
  }

  private fun loopJourney(body: String, max: Int, platform: Platform) = Journey(
    name = "loop-contract",
    app = APP_ID,
    platform = platform,
    steps = listOf(JourneyStep.Loop(body, "page is ready", max)),
  )

  private fun loopOrchestrator(
    session: DeviceSession,
    inspector: InspectorAgent,
    navigator: NavigatorAgent = NavigatorAgent("unused") { _, _ -> modelReply(error("unexpected navigator")) },
    recorder: JourneyArtifactRecorder = NoOpJourneyArtifactRecorder,
  ) = Orchestrator(session, { navigator }, { inspector }, artifactRecorder = recorder)

  private class LoopSession(
    val events: MutableList<String>,
    override val platform: Platform,
    val failAt: Int? = null,
    val onPress: suspend () -> Unit = {},
  ) : DeviceSession {
    var commands = 0
    val executedActionFlows = mutableListOf<ActionFlow>()
    override suspend fun executeActions(flow: ActionFlow): FlowResult {
      executedActionFlows += flow
      if (flow.actions == listOf(Interaction.LaunchApp())) return FlowResult(true)
      commands++
      events += "flow"
      return FlowResult(commands != failAt, output = "command failed")
    }
    override suspend fun executeFlow(yaml: String): FlowResult = error("Internal YAML execution forbidden")
    override suspend fun pressKey(keyName: String) {
      events += keyName
      onPress()
    }
    override suspend fun captureHierarchyTree() = HierarchyNode(attributes = mapOf("text" to "Home"))
    override suspend fun containsText(text: String, ignoreCase: Boolean) = false
    override suspend fun captureScreenshot(output: Path) = Unit
    override suspend fun waitForAnimationToEnd() = Unit
    override fun close() = Unit
  }

  private class FakeDeviceSession(
    private val screens: ArrayDeque<HierarchyNode> = ArrayDeque(),
    private val onExecuteActions: (String) -> Unit = {},
    private val onCaptureScreenshot: (Path) -> Unit = {},
    private val launchResult: FlowResult = FlowResult(success = true),
  ) : DeviceSession {
    override val platform: Platform = Platform.ANDROID_MOBILE
    val renderedActionFlows = mutableListOf<String>()
    val capturedScreenshotPaths = mutableListOf<Path>()

    var hierarchyCaptures = 0
    val executedActionFlows = mutableListOf<ActionFlow>()
    override suspend fun executeActions(flow: ActionFlow): FlowResult {
      executedActionFlows += flow
      val yaml = ActionFlowYamlRenderer.render(flow)
      onExecuteActions(yaml)
      renderedActionFlows += yaml
      return if (flow.actions == listOf(Interaction.LaunchApp())) launchResult else FlowResult(success = true)
    }
    override suspend fun executeFlow(yaml: String): FlowResult = error("Internal YAML execution forbidden")
    override suspend fun pressKey(keyName: String) = Unit
    override suspend fun captureHierarchyTree(): HierarchyNode {
      hierarchyCaptures++
      return screens.removeFirstOrNull() ?: HierarchyNode(attributes = mapOf("text" to "Home"))
    }
    override suspend fun captureScreenshot(output: Path) {
      onCaptureScreenshot(output)
      capturedScreenshotPaths.add(output)
      Files.write(output, byteArrayOf(1, 2, 3))
    }
    override suspend fun waitForAnimationToEnd() = Unit

    override fun close() = Unit
  }

  private class RecordingArtifactRecorder(
    private val screenshotDirectory: Path? = null,
  ) : JourneyArtifactRecorder {
    val generatedFlows = mutableListOf<String>()
    val hierarchies = mutableListOf<String>()
    val screenshotRequests = mutableListOf<Int>()
    val screenshotPaths = mutableListOf<Path>()

    override suspend fun saveGeneratedFlow(segmentIndex: Int, label: String, yaml: String): String {
      generatedFlows += "$segmentIndex:$label:$yaml"
      return "flows/segment-${segmentIndex.toString().padStart(3, '0')}-$label.yaml"
    }

    override suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String {
      hierarchies += hierarchy
      return "evidence/segment-${segmentIndex.toString().padStart(3, '0')}-tree.txt"
    }

    override suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact {
      screenshotRequests += segmentIndex
      val path = if (screenshotDirectory != null) {
        Files.createTempFile(screenshotDirectory, "verity-visual-test", ".png")
      } else {
        Files.createTempFile("verity-visual-test", ".png")
      }
      screenshotPaths.add(path)
      return JourneyScreenshotArtifact(
        path = path,
        relativePath = "evidence/segment-${segmentIndex.toString().padStart(3, '0')}-visual.png",
      )
    }
  }

  private class ThrowingArtifactRecorder : JourneyArtifactRecorder {
    override suspend fun saveGeneratedFlow(segmentIndex: Int, label: String, yaml: String): String = error("artifact unavailable")

    override suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String = error("artifact unavailable")

    override suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact = error("artifact unavailable")
  }

  private class StaticScreenshotArtifactRecorder(
    private val path: Path,
  ) : JourneyArtifactRecorder {
    override suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact = JourneyScreenshotArtifact(
      path = path,
      relativePath = "evidence/segment-${segmentIndex.toString().padStart(3, '0')}-visual.png",
    )
  }

  private companion object {
    const val APP_ID = "com.example.app"
    const val LAUNCH_FLOW = "appId: \"$APP_ID\"\n---\n- launchApp"
    fun screen(text: String) = HierarchyNode(attributes = mapOf("text" to text))

    /** A bounds-only wrapper around one text node: `PROMPT` drops the wrapper line, `CONTENT` keeps it. */
    val wrappedHome = HierarchyNode(
      attributes = mapOf("bounds" to "[0,0][1000,500]"),
      children = listOf(HierarchyNode(attributes = mapOf("text" to "Home", "bounds" to "[10,10][100,50]"))),
    )
    fun flow(command: String) = "appId: \"$APP_ID\"\n---\n$command"

    fun deleteRecursively(path: Path) {
      if (!Files.exists(path)) return
      Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }
}
