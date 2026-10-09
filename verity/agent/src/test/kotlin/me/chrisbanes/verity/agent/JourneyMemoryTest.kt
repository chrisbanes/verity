package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.endsWith
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.FocusNodeArtifact
import me.chrisbanes.verity.core.result.TrailGranularity
import me.chrisbanes.verity.core.result.TrailOrigin
import me.chrisbanes.verity.device.DeviceSession

class JourneyMemoryTest {
  private val source = TrailSource(segment = 2)

  private fun focused(vararg ids: String) = HierarchyNode(
    children = ids.map { HierarchyNode(attributes = mapOf("resource-id" to it), states = setOf("focused")) },
  )

  private class FakeSession(var tree: (() -> HierarchyNode)? = null) : DeviceSession {
    var captures = 0
    override val platform = Platform.ANDROID_TV
    override suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode {
      captures++
      return (tree ?: return super.captureHierarchyTree(timeout))()
    }
    override suspend fun captureHierarchyTree(): HierarchyNode = error("unbounded capture is not used")
    override suspend fun executeFlow(yaml: String) = FlowResult(true)
    override suspend fun executeActions(flow: ActionFlow) = FlowResult(true)
    override suspend fun pressKey(keyName: String) = Unit
    override suspend fun captureScreenshot(output: Path) = Unit
    override suspend fun shell(command: String) = ""
    override suspend fun waitForAnimationToEnd() = Unit
    override fun close() = Unit
  }

  private fun withMemory(session: FakeSession = FakeSession(), test: suspend (JourneyMemory, Path) -> Unit) = runTest {
    val root = Files.createTempDirectory("journey-memory-test")
    val memory = JourneyMemory(session, tempRoot = root)
    try {
      test(memory, root)
    } finally {
      memory.close()
      root.toFile().deleteRecursively()
    }
  }

  private fun file(root: Path, name: String, vararg bytes: Byte): Path = root.resolve(name).also { Files.write(it, bytes) }

  @Test
  fun `records interaction with focus before and after`() {
    val session = FakeSession()
    var current = focused("menu:home")
    session.tree = { current }
    withMemory(session) { memory, _ ->
      memory.recordExecution<Unit>(source, TrailGranularity.INTERACTION, listOf("Press D-pad down")) {
        current = focused("menu:settings")
      }
      val entry = memory.trail().entries.single()
      assertThat(entry.granularity).isEqualTo(TrailGranularity.INTERACTION)
      assertThat(entry.origin).isEqualTo(TrailOrigin.ACTIONS)
      assertThat(entry.segment).isEqualTo(2)
      assertThat(entry.instructions).containsExactly("Press D-pad down")
      assertThat(entry.succeeded).isTrue()
      assertThat(entry.focusBefore).isEqualTo(listOf(FocusNodeArtifact("/0", "menu:home")))
      assertThat(entry.focusAfter).isEqualTo(listOf(FocusNodeArtifact("/0", "menu:settings")))
    }
  }

  @Test
  fun `unsupported bounded capture records unknown focus and still runs the block once`() {
    var runs = 0
    withMemory { memory, _ ->
      memory.recordExecution(source, TrailGranularity.FLOW, listOf("a", "b")) { runs++ }
      val entry = memory.trail().entries.single()
      assertThat(runs).isEqualTo(1)
      assertThat(entry.focusBefore).isNull()
      assertThat(entry.focusAfter).isNull()
      assertThat(memory.inspectionContext().referenceText).contains("focus before: unknown; after: unknown")
    }
  }

  @Test
  fun `no focused node is empty rather than unknown`() {
    val session = FakeSession { HierarchyNode() }
    withMemory(session) { memory, _ ->
      memory.recordExecution(source, TrailGranularity.INTERACTION, listOf("Tap")) { }
      val entry = memory.trail().entries.single()
      assertThat(entry.focusBefore).isEqualTo(emptyList())
      assertThat(entry.focusAfter).isEqualTo(emptyList())
      assertThat(memory.inspectionContext().referenceText).contains("focus before: none; after: none")
    }
  }

  @Test
  fun `flow is one entry with exactly two captures`() {
    val session = FakeSession { HierarchyNode() }
    withMemory(session) { memory, _ ->
      memory.recordExecution(source, TrailGranularity.FLOW, listOf("a", "b", "c")) { }
      assertThat(session.captures).isEqualTo(2)
      assertThat(memory.trail().entries).hasSize(1)
    }
  }

  @Test
  fun `execution failure records unsuccessful entry and rethrows, cancellation records nothing`() {
    withMemory { memory, _ ->
      assertFailsWith<InteractionExecutionFailure> {
        memory.recordExecution<Unit>(source, TrailGranularity.INTERACTION, listOf("Tap")) {
          throw InteractionExecutionFailure(FlowResult(false, "boom"))
        }
      }
      assertThat(memory.trail().entries.single().succeeded).isFalse()
      assertFailsWith<CancellationException> {
        memory.recordExecution<Unit>(source, TrailGranularity.INTERACTION, listOf("Tap")) { throw CancellationException("stop") }
      }
      assertFailsWith<IllegalStateException> {
        memory.recordExecution<Unit>(source, TrailGranularity.INTERACTION, listOf("Tap")) { error("other") }
      }
      assertThat(memory.trail().entries).hasSize(1)
    }
  }

  @Test
  fun `unsuccessful outcome is recorded`() {
    withMemory { memory, _ ->
      memory.recordExecution(source, TrailGranularity.FLOW, listOf("x"), outcome = { it.success }) { FlowResult(false, "bad") }
      assertThat(memory.trail().entries.single().succeeded).isFalse()
    }
  }

  @Test
  fun `trail keeps the most recent entries and counts dropped ones`() {
    withMemory { memory, _ ->
      repeat(25) { index -> memory.recordExecution(source, TrailGranularity.INTERACTION, listOf("step $index")) { } }
      val trail = memory.trail()
      assertThat(trail.entries.map { it.instructions.single() }).isEqualTo((5 until 25).map { "step $it" })
      assertThat(trail.droppedEntries).isEqualTo(5)
      assertThat(trail.maxEntries).isEqualTo(20)
      assertThat(trail.maxTextChars).isEqualTo(300)
      assertThat(trail.maxFocusedNodes).isEqualTo(5)
      assertThat(memory.inspectionContext().referenceText).contains("(5 older omitted)")
    }
  }

  @Test
  fun `verdicts keep the most recent and note omitted ones`() {
    withMemory { memory, _ ->
      repeat(12) { memory.recordVerdict("assertion $it", it % 2 == 0, "reason $it") }
      val text = memory.inspectionContext().referenceText
      assertThat(text).contains("(2 older omitted)")
      assertThat(text).doesNotContain("assertion 1:")
      assertThat(text).contains("- [passed] assertion 2: reason 2")
      assertThat(text).contains("- [failed] assertion 11: reason 11")
    }
  }

  @Test
  fun `long text and many focused nodes are truncated and flagged`() {
    val session = FakeSession { focused("a", "b", "c", "d", "e", "f", "g") }
    withMemory(session) { memory, _ ->
      val long = "x".repeat(301)
      memory.recordExecution(source, TrailGranularity.INTERACTION, listOf(long)) { }
      val entry = memory.trail().entries.single()
      assertThat(entry.instructions.single().length).isEqualTo(300)
      assertThat(entry.instructions.single()).endsWith("…")
      assertThat(entry.focusBefore!!.map { it.resourceId }).containsExactly("a", "b", "c", "d", "e")
      assertThat(entry.truncated).isTrue()

      memory.recordVerdict(long, true, long)
      val line = memory.inspectionContext().referenceText.lines().single { it.startsWith("- [passed]") }
      assertThat(line).isEqualTo("- [passed] ${"x".repeat(299)}…: ${"x".repeat(299)}…")
    }
  }

  @Test
  fun `exactly bounded values are not flagged`() {
    val session = FakeSession { focused("a", "b", "c", "d", "e") }
    withMemory(session) { memory, _ ->
      memory.recordExecution(source, TrailGranularity.INTERACTION, listOf("x".repeat(300))) { }
      assertThat(memory.trail().entries.single().truncated).isFalse()
    }
  }

  @Test
  fun `screenshots are copied, deduplicated, bounded to two and removed on close`() {
    withMemory { memory, root ->
      assertThat(memory.inspectionContext().referenceScreenshots).isEmpty()
      assertThat(memory.inspectionContext().referenceText).isEqualTo("")

      val a = file(root, "a.png", 1)
      memory.recordScreenshot(a)
      val one = memory.inspectionContext()
      assertThat(one.referenceScreenshots).hasSize(1)
      assertThat(one.referenceText).contains("only earlier screenshot")

      memory.recordScreenshot(file(root, "b.png", 2))
      memory.recordScreenshot(file(root, "c.png", 3))
      val two = memory.inspectionContext()
      assertThat(two.referenceScreenshots.map { Files.readAllBytes(it).toList() }).containsExactly(listOf<Byte>(1), listOf<Byte>(3))
      assertThat(two.referenceText).contains("Reference screenshot 1 is the first earlier screenshot")
      assertThat(two.referenceText).contains("Reference screenshot 2 is the most recent earlier screenshot")

      Files.write(a, byteArrayOf(9))
      Files.delete(a)
      assertThat(Files.readAllBytes(two.referenceScreenshots.first()).toList()).isEqualTo(listOf<Byte>(1))

      val copies = two.referenceScreenshots.map { it.parent }.distinct().single()
      assertThat(Files.list(copies).use { it.count() }).isEqualTo(2L)
      memory.close()
      assertThat(Files.exists(copies)).isFalse()
    }
  }

  @Test
  fun `missing source screenshot is ignored and keeps the previous reference`() {
    withMemory { memory, root ->
      memory.recordScreenshot(file(root, "a.png", 1))
      memory.recordScreenshot(root.resolve("missing.png"))
      val context = memory.inspectionContext()
      assertThat(context.referenceScreenshots).hasSize(1)
      assertThat(Files.readAllBytes(context.referenceScreenshots.single()).toList()).isEqualTo(listOf<Byte>(1))
    }
  }
}
