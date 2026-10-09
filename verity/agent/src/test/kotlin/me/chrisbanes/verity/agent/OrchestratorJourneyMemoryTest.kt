package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.FocusNodeArtifact
import me.chrisbanes.verity.core.result.TrailGranularity
import me.chrisbanes.verity.core.result.TrailOrigin
import me.chrisbanes.verity.device.DeviceSession

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OrchestratorJourneyMemoryTest {
  /** Focus follows the number of executions so far, and `events` shows what surrounded each execution. */
  private class Session(
    override val platform: Platform,
    private val containsTextResults: ArrayDeque<Boolean> = ArrayDeque(),
  ) : DeviceSession {
    val events = mutableListOf<String>()
    private var executions = 0
    private var screenshots = 0

    private fun focusedTree() = HierarchyNode(
      children = listOf(HierarchyNode(attributes = mapOf("resource-id" to "focus:$executions", "text" to "Home"), states = setOf("focused"))),
    )

    override suspend fun executeActions(flow: ActionFlow): FlowResult {
      if (flow.actions.singleOrNull() == Interaction.LaunchApp()) return FlowResult(true)
      events += "flow"
      executions++
      return FlowResult(true)
    }

    override suspend fun pressKey(keyName: String) {
      events += "key"
      executions++
    }

    override suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode {
      events += "capture"
      return focusedTree()
    }

    override suspend fun captureHierarchyTree(): HierarchyNode = focusedTree()
    override suspend fun containsText(text: String, ignoreCase: Boolean): Boolean = containsTextResults.removeFirstOrNull() ?: false
    override suspend fun captureScreenshot(output: Path) {
      Files.write(output, byteArrayOf((++screenshots).toByte()))
    }

    override suspend fun executeFlow(yaml: String) = error("unused")
    override suspend fun shell(command: String) = ""
    override suspend fun waitForAnimationToEnd() = Unit
    override fun close() = Unit
  }

  private class Call(val kind: String, val message: String, val referenceBytes: List<Byte>, val referencePaths: List<Path>, val currentPath: Path? = null)

  private class Inspector(private val passes: ArrayDeque<Boolean> = ArrayDeque()) {
    val calls = mutableListOf<Call>()
    private fun reply(): String = """{"passed":${passes.removeFirstOrNull() ?: true},"reasoning":"reason ${calls.size}"}"""
    fun agent() = InspectorAgent(
      evaluateTreeContent = { _, message, refs ->
        calls += Call("tree", message, refs.map { Files.readAllBytes(it)[0] }, refs)
        inspectionReply(reply())
      },
      evaluateVisualContent = { _, message, current, refs ->
        calls += Call("visual", message, refs.map { Files.readAllBytes(it)[0] }, refs, current)
        inspectionReply(reply())
      },
    )
  }

  private class FixedScreenshotRecorder(private val path: Path) : JourneyArtifactRecorder {
    override suspend fun screenshotPath(segmentIndex: Int) = JourneyScreenshotArtifact(path, "evidence/segment-$segmentIndex-visual.png")
  }

  private val flowOfThree = """{"actions":[{"type":"defaultScroll"},{"type":"defaultScroll"},{"type":"defaultScroll"}]}"""

  private fun orchestrator(
    session: DeviceSession,
    inspector: Inspector = Inspector(),
    navigatorReply: String = flowOfThree,
    recorder: JourneyArtifactRecorder = NoOpJourneyArtifactRecorder,
    nowNanos: () -> Long = System::nanoTime,
  ) = Orchestrator(
    session,
    navigatorFactory = { NavigatorAgent("unused") { _, _ -> modelReply(navigatorReply) } },
    inspectorFactory = inspector::agent,
    artifactRecorder = recorder,
    nowNanos = nowNanos,
  )

  private fun journey(platform: Platform, vararg steps: JourneyStep) = Journey("j", "com.example.app", platform, steps.toList())

  @Test
  fun `fast path interaction and earlier verdict reach the next tree assertion`() = runTest {
    val session = Session(Platform.ANDROID_TV)
    val inspector = Inspector()
    val result = orchestrator(session, inspector).run(
      journey(
        Platform.ANDROID_TV,
        JourneyStep.Action("Press D-pad down"),
        JourneyStep.Assert("First", AssertMode.TREE),
        JourneyStep.Assert("Second", AssertMode.TREE),
      ),
    )

    assertThat(result.passed).isEqualTo(true)
    val second = inspector.calls[1].message
    assertThat(second).contains("Reference context (earlier observations, not proof of the current condition):")
    assertThat(second).contains("[passed] First: reason 1")
    assertThat(second).contains("segment 0, interaction (actions), succeeded: \"Press D-pad down\"; focus before: focus:0 (/0); after: focus:1 (/0)")
    val entry = result.trail!!.entries.single()
    assertThat(entry.granularity).isEqualTo(TrailGranularity.INTERACTION)
    assertThat(entry.focusBefore).isEqualTo(listOf(FocusNodeArtifact("/0", "focus:0")))
    assertThat(entry.focusAfter).isEqualTo(listOf(FocusNodeArtifact("/0", "focus:1")))
    assertThat(inspector.calls.flatMap { it.referencePaths }).isEmpty()
  }

  @Test
  fun `slow path flow is one entry with exactly two surrounding captures`() = runTest {
    val session = Session(Platform.ANDROID_MOBILE)
    val result = orchestrator(session).run(
      journey(Platform.ANDROID_MOBILE, JourneyStep.Action("navigate to settings page")),
    )

    assertThat(session.events).containsExactly("capture", "flow", "capture")
    val entry = result.trail!!.entries.single()
    assertThat(entry.granularity).isEqualTo(TrailGranularity.FLOW)
    assertThat(entry.instructions).containsExactly("navigate to settings page")
    assertThat(entry.focusBefore).isEqualTo(listOf(FocusNodeArtifact("/0", "focus:0")))
    assertThat(entry.focusAfter).isEqualTo(listOf(FocusNodeArtifact("/0", "focus:1")))
  }

  @Test
  fun `multi instruction slow path flow lists every instruction in one entry`() = runTest {
    val session = Session(Platform.ANDROID_MOBILE)
    val result = orchestrator(session).run(
      journey(Platform.ANDROID_MOBILE, JourneyStep.Action("open the menu"), JourneyStep.Action("pick the third item"), JourneyStep.Action("go back")),
    )

    assertThat(session.events).containsExactly("capture", "flow", "capture")
    assertThat(result.trail!!.entries.single().instructions).containsExactly("open the menu", "pick the third item", "go back")
  }

  @Test
  fun `fast path loop iterations are entries and later conditions see earlier iterations`() = runTest {
    val session = Session(Platform.ANDROID_TV)
    val inspector = Inspector(ArrayDeque(listOf(false, false, true)))
    val result = orchestrator(session, inspector).run(
      journey(Platform.ANDROID_TV, JourneyStep.Loop("Press D-pad down", "Ready screen", 5)),
    )

    assertThat(result.passed).isEqualTo(true)
    assertThat(result.trail!!.entries.map { it.origin to it.iteration }).containsExactly(TrailOrigin.LOOP to 0, TrailOrigin.LOOP to 1)
    assertThat(inspector.calls[0].message).doesNotContain("Reference context")
    assertThat(inspector.calls[1].message).contains("interaction (loop #0)")
    assertThat(inspector.calls[1].message).doesNotContain("loop #1")
    assertThat(inspector.calls[2].message).contains("loop #1")
  }

  @Test
  fun `slow path loop records one flow entry per iteration`() = runTest {
    val session = Session(Platform.ANDROID_MOBILE)
    val inspector = Inspector(ArrayDeque(listOf(false, false, true)))
    val result = orchestrator(session, inspector).run(
      journey(Platform.ANDROID_MOBILE, JourneyStep.Loop("navigate onward", "Ready screen", 5)),
    )

    assertThat(result.trail!!.entries.map { it.granularity to it.iteration }).containsExactly(TrailGranularity.FLOW to 0, TrailGranularity.FLOW to 1)
  }

  @Test
  fun `scroll to find is recorded before the target interaction`() = runTest {
    val session = Session(Platform.ANDROID_MOBILE, ArrayDeque(listOf(false, true)))
    val result = orchestrator(session, navigatorReply = "DOWN").run(
      journey(Platform.ANDROID_MOBILE, JourneyStep.Action("tap Settings")),
    )

    val entries = result.trail!!.entries
    assertThat(entries.map { it.origin }).containsExactly(TrailOrigin.SCROLL_TO_FIND, TrailOrigin.ACTIONS)
    assertThat(entries.map { it.granularity }).containsExactly(TrailGranularity.INTERACTION, TrailGranularity.INTERACTION)
    assertThat(entries.map { it.instructions }).containsExactly(listOf("tap Settings"), listOf("tap Settings"))
  }

  @Test
  fun `visual assertions get at most two distinct earlier screenshots, never the current one`() = runTest {
    val directory = Files.createTempDirectory("memory-visual")
    try {
      val current = directory.resolve("current.png")
      val inspector = Inspector()
      val steps: Array<JourneyStep> = (1..4).map { JourneyStep.Assert("Visual $it", AssertMode.VISUAL) }.toTypedArray()
      orchestrator(Session(Platform.ANDROID_MOBILE), inspector, recorder = FixedScreenshotRecorder(current))
        .run(journey(Platform.ANDROID_MOBILE, *steps))

      assertThat(inspector.calls.map { it.referenceBytes }).containsExactly(
        emptyList<Byte>(),
        listOf<Byte>(1),
        listOf<Byte>(1, 2),
        listOf<Byte>(1, 3),
      )
      inspector.calls.forEach { call -> assertThat(call.referencePaths.contains(call.currentPath)).isEqualTo(false) }
      assertThat(inspector.calls[2].message).contains("Reference screenshot 1 is the first earlier screenshot")
      assertThat(inspector.calls[2].message).contains("Reference screenshot 2 is the most recent earlier screenshot")
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `tree assertions receive no screenshots even after a visual one`() = runTest {
    val inspector = Inspector()
    orchestrator(Session(Platform.ANDROID_MOBILE), inspector).run(
      journey(Platform.ANDROID_MOBILE, JourneyStep.Assert("Look", AssertMode.VISUAL), JourneyStep.Assert("Tree", AssertMode.TREE)),
    )

    assertThat(inspector.calls[1].kind).isEqualTo("tree")
    assertThat(inspector.calls[1].referencePaths).isEmpty()
    assertThat(inspector.calls[1].message).contains("[passed] Look: reason 1")
  }

  @Test
  fun `inspector backed wait receives earlier verdict and trail`() = runTest {
    val inspector = Inspector()
    orchestrator(Session(Platform.ANDROID_TV), inspector, nowNanos = { testScheduler.currentTime * 1_000_000 }).run(
      journey(
        Platform.ANDROID_TV,
        JourneyStep.Action("Press D-pad down"),
        JourneyStep.Assert("First", AssertMode.TREE),
        JourneyStep.Wait("Settled screen", 3),
      ),
    )

    val waitMessage = inspector.calls.last().message
    assertThat(waitMessage).contains("[passed] First: reason 1")
    assertThat(waitMessage).contains("interaction (actions)")
  }

  @Test
  fun `memory never carries over to the next journey on a reused orchestrator`() = runTest {
    val inspector = Inspector()
    val orchestrator = orchestrator(Session(Platform.ANDROID_MOBILE, ArrayDeque(listOf(true))), inspector)
    val a = orchestrator.run(
      journey(Platform.ANDROID_MOBILE, JourneyStep.Action("tap Settings"), JourneyStep.Assert("Look", AssertMode.VISUAL), JourneyStep.Assert("Tree", AssertMode.TREE)),
    )
    val callsForA = inspector.calls.size
    val b = orchestrator.run(
      journey(Platform.ANDROID_MOBILE, JourneyStep.Assert("Other", AssertMode.VISUAL), JourneyStep.Action("scroll down"), JourneyStep.Assert("Later", AssertMode.TREE)),
    )

    assertThat(a.trail!!.entries).hasSize(1)
    val firstOfB = inspector.calls[callsForA]
    assertThat(firstOfB.message).doesNotContain("Reference context")
    assertThat(firstOfB.referencePaths).isEmpty()
    assertThat(b.trail!!.entries.map { it.instructions }).containsExactly(listOf("scroll down"))
    assertThat(inspector.calls.last().message).doesNotContain("tap Settings")
    assertThat(inspector.calls.last().message).doesNotContain("[passed] Look")
  }

  @Test
  fun `result trail carries caps and dropped count`() = runTest {
    val steps = (1..25).map { JourneyStep.Action("Press D-pad down") }.toTypedArray()
    val result = orchestrator(Session(Platform.ANDROID_TV)).run(journey(Platform.ANDROID_TV, *steps))
    val trail = result.trail!!
    assertThat(trail.entries).hasSize(20)
    assertThat(trail.droppedEntries).isEqualTo(5)
    assertThat(trail.maxEntries).isEqualTo(20)
    assertThat(trail.maxTextChars).isEqualTo(300)
    assertThat(trail.maxFocusedNodes).isEqualTo(5)
  }

  @Test
  fun `unsupported bounded capture leaves focus unknown without changing the outcome`() = runTest {
    val session = object : DeviceSession by Session(Platform.ANDROID_TV) {
      override suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode = throw UnsupportedOperationException("no bounded capture")
    }
    val result = orchestrator(session).run(journey(Platform.ANDROID_TV, JourneyStep.Action("Press D-pad down")))

    assertThat(result.passed).isEqualTo(true)
    val entry = result.trail!!.entries.single()
    assertThat(entry.focusBefore).isNull()
    assertThat(entry.focusAfter).isNull()
  }
}
