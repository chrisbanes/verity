package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession

class LoopBatchingTest {
  // The label is a child so FocusDetector's sibling match cannot pair another card's text with focus.
  private fun card(index: Int, row: Int, focused: Boolean) = HierarchyNode(
    attributes = mapOf("resource-id" to "card-$index", "bounds" to "[0,${row * 100}][300,${row * 100 + 100}]"),
    states = if (focused) setOf("focused", "clickable") else setOf("clickable"),
    children = listOf(HierarchyNode(mapOf("text" to "Card $index", "bounds" to "[10,${row * 100 + 10}][290,${row * 100 + 40}]"))),
  )

  /** A vertical list showing [visible], with [focused] (if visible) holding focus. */
  private fun list(visible: IntRange, focused: Int?, extra: List<HierarchyNode> = emptyList()) = HierarchyNode(children = visible.map { card(it, it - visible.first, it == focused) } + extra)

  private fun session(platform: Platform = Platform.ANDROID_TV, tree: (presses: Int) -> HierarchyNode): FakeDeviceSession {
    lateinit var fake: FakeDeviceSession
    fake = FakeDeviceSession(platform, hierarchyProvider = { tree(fake.pressedKeys.size).also { lastTree = it } })
    return fake
  }

  /** Focus moves one card per press over cards 0..[last], stopping at the end. */
  private fun linear(last: Int = 9, platform: Platform = Platform.ANDROID_TV) = session(platform) { list(0..last, minOf(it, last)) }

  private class Inspector(private val replies: (call: Int) -> String) {
    val messages = mutableListOf<String>()
    val agent = InspectorAgent(
      evaluateTreeContent = { _, message, _ ->
        messages += message
        inspectionReply(replies(messages.size))
      },
      evaluateVisualContent = { _, message, _, _ ->
        messages += message
        inspectionReply(replies(messages.size))
      },
    )
  }

  private val unusedInspector = Inspector { error("unexpected inspection") }

  private var lastTree: HierarchyNode? = null

  /** Answers like an accurate model from the tree the check just captured: passed, the target's bounds, or notVisible. */
  private fun oracle(target: Int) = Inspector {
    val cards = checkNotNull(lastTree).children
    val focused = cards.firstOrNull { "focused" in it.states }?.attributes?.get("resource-id")
    val bounds = cards.firstOrNull { it.attributes["resource-id"] == "card-$target" }?.attributes?.get("bounds")
    when {
      focused == "card-$target" -> """{"passed":true,"reasoning":"done"}"""
      bounds != null -> """{"passed":false,"reasoning":"no","target":"$bounds"}"""
      else -> """{"passed":false,"reasoning":"no","target":"notVisible"}"""
    }
  }

  private suspend fun run(session: FakeDeviceSession, until: String, max: Int, inspector: Inspector = unusedInspector, body: String = "Press D-pad down", platform: Platform = Platform.ANDROID_TV): SegmentResult {
    val navigator = NavigatorAgent("unused") { _, _ -> modelReply(error("unexpected navigator")) }
    val journey = Journey("batch", "app", platform, listOf(JourneyStep.Loop(body, until, max)))
    // Visual checks need a non-empty screenshot file.
    val device = object : DeviceSession by session {
      override suspend fun captureScreenshot(output: Path) {
        withContext(Dispatchers.IO) { Files.write(output, byteArrayOf(1)) }
      }
    }
    return Orchestrator(device, { navigator }, { inspector.agent }).run(journey).segments.single()
  }

  @Test fun `tree target on screen takes one batch and one more check`() = runTest {
    val session = linear()
    val inspector = Inspector { call -> if (call == 1) """{"passed":false,"reasoning":"not yet","target":"[0,600][300,700]"}""" else """{"passed":true,"reasoning":"done"}""" }
    val segment = run(session, "the focused card is Card 6", 20, inspector)
    assertThat(segment.passed).isTrue()
    assertThat(inspector.messages.size).isEqualTo(2)
    assertThat(inspector.messages.first()).contains(InspectorAgent.LOOP_HINTS)
    assertThat(session.pressedKeys.size).isEqualTo(6)
    assertThat(segment.loop?.iterations).isEqualTo(6)
    assertThat(segment.loop?.pressesPerCheck).isEqualTo(listOf(6))
  }

  @Test fun `focus-tier conditions are checked after every press`() = runTest {
    val session = linear()
    val segment = run(session, "Card 6 is focused", 20)
    assertThat(segment.passed).isTrue()
    assertThat(segment.loop?.pressesPerCheck).isEqualTo(List(6) { 1 })
  }

  @Test fun `off-screen target batches past the visible cards and plans again from each new tree`() = runTest {
    // Four cards are visible, and the list scrolls to keep one card above focus.
    val session = session { presses ->
      val start = maxOf(0, presses - 1).coerceAtMost(6)
      list(start..start + 3, presses)
    }
    val segment = run(session, "the focused card is Card 9", 20, oracle(9))
    assertThat(segment.passed).isTrue()
    assertThat(segment.loop?.pressesPerCheck).isEqualTo(listOf(3, 2, 2, 2))
    assertThat(segment.loop?.iterations).isEqualTo(9)
  }

  @Test fun `an under-counted batch falls back to single presses and still satisfies the loop`() = runTest {
    // The device has a focus stop after card 0 that the tree shows only while it is focused.
    val hidden = HierarchyNode(attributes = mapOf("resource-id" to "hidden"), states = setOf("focused"))
    val session = session { presses ->
      when (presses) {
        0 -> list(0..9, 0)
        1 -> list(0..9, null, listOf(hidden))
        else -> list(0..9, presses - 1)
      }
    }
    val segment = run(session, "the focused card is Card 6", 20, oracle(6))
    assertThat(segment.passed).isTrue()
    assertThat(segment.loop?.pressesPerCheck).isEqualTo(listOf(6, 1))
    assertThat(segment.loop?.iterations).isEqualTo(7)
  }

  @Test fun `an over-counted batch stops when focus reaches the target early`() = runTest {
    // Device focus skips card 3, so card 6 is reached on the fifth of six predicted presses.
    val session = session { presses -> list(0..9, if (presses < 3) presses else minOf(presses + 1, 9)) }
    val inspector = oracle(6)
    val segment = run(session, "the focused card is Card 6", 8, inspector)
    assertThat(segment.passed).isTrue()
    assertThat(inspector.messages.size).isEqualTo(2)
    assertThat(segment.loop?.pressesPerCheck).isEqualTo(listOf(5))
    assertThat(segment.loop?.iterations).isEqualTo(5)
  }

  @Test fun `a target the focus order skips falls back to single presses and exhausts the loop`() = runTest {
    // Device focus skips card 6 itself, so six presses land on card 7 and card 6 is never focused.
    val session = session { presses -> list(0..9, if (presses < 6) presses else minOf(presses + 1, 9)) }
    val segment = run(session, "the focused card is Card 6", 8, oracle(6))
    assertThat(segment.passed).isFalse()
    assertThat(segment.loop?.pressesPerCheck).isEqualTo(listOf(6, 1, 1))
    assertThat(segment.loop?.iterations).isEqualTo(8)
  }

  @Test fun `a target around the current focus gives no prediction`() = runTest {
    // The model names the whole list, which already contains focus.
    val session = session { presses -> list(0..9, presses).copy(attributes = mapOf("bounds" to "[0,0][300,1000]")) }
    val inspector = Inspector { call -> if (call < 3) """{"passed":false,"reasoning":"no","target":"[0,0][300,1000]"}""" else """{"passed":true,"reasoning":"done"}""" }
    val segment = run(session, "the focused card is Card 2", 20, inspector)
    assertThat(segment.loop?.pressesPerCheck).isEqualTo(listOf(1, 1))
  }

  @Test fun `an edge stops the batch early and later bodies are checked one at a time`() = runTest {
    val session = linear(last = 3)
    val inspector = Inspector { """{"passed":false,"reasoning":"no","estimatedIterations":5}""" }
    val segment = run(session, "the playback is near the end", 5, inspector)
    assertThat(segment.passed).isFalse()
    assertThat(segment.loop?.pressesPerCheck).isEqualTo(listOf(4, 1))
    assertThat(session.pressedKeys.size).isEqualTo(5)
  }

  @Test fun `a wrap back to an earlier focus stops the batch`() = runTest {
    val session = session { presses -> list(0..3, presses % 4) }
    val inspector = Inspector { """{"passed":false,"reasoning":"no","estimatedIterations":6}""" }
    val segment = run(session, "the playback is near the end", 8, inspector)
    assertThat(segment.loop?.pressesPerCheck).isEqualTo(listOf(4, 1, 1, 1, 1))
    assertThat(session.pressedKeys.size).isLessThanOrEqualTo(8)
    assertThat(segment.loop?.iterations).isEqualTo(8)
  }

  @Test fun `the remaining limit caps a batch`() = runTest {
    val session = linear()
    val segment = run(session, "the focused card is Card 6", 4, oracle(6))
    assertThat(segment.passed).isFalse()
    assertThat(session.pressedKeys.size).isEqualTo(4)
    assertThat(segment.loop?.iterations).isEqualTo(4)
    assertThat(segment.loop?.pressesPerCheck).isEqualTo(listOf(4))
  }

  @Test fun `an estimate without a target presses that many before checking`() = runTest {
    val session = linear()
    val inspector = Inspector { call -> if (call == 1) """{"passed":false,"reasoning":"no","estimatedIterations":5}""" else """{"passed":true,"reasoning":"done"}""" }
    val segment = run(session, "the playback is near the end", 20, inspector)
    assertThat(segment.passed).isTrue()
    assertThat(inspector.messages.size).isEqualTo(2)
    assertThat(session.pressedKeys.size).isEqualTo(5)
    assertThat(segment.loop?.pressesPerCheck).isEqualTo(listOf(5))
  }

  @Test fun `ineligible loops check after every body and never send hints`() = runTest {
    val cases = listOf(
      Triple("Press D-pad down; press D-pad right", "the focused card is Card 6", Platform.ANDROID_TV),
      Triple("Press select", "the focused card is Card 6", Platform.ANDROID_TV),
      Triple("Press D-pad down", "visually the focused card is Card 6", Platform.ANDROID_TV),
      Triple("Swipe left", "the focused card is Card 6", Platform.ANDROID_MOBILE),
    )
    for ((body, until, platform) in cases) {
      val inspector = Inspector { """{"passed":false,"reasoning":"no","target":"[0,600][300,700]","estimatedIterations":5}""" }
      val segment = run(linear(platform = platform), until, 2, inspector, body, platform)
      assertThat(inspector.messages.size).isEqualTo(3)
      assertThat(inspector.messages.none { InspectorAgent.LOOP_HINTS in it }).isTrue()
      assertThat(segment.loop?.iterations).isEqualTo(2)
      assertThat(segment.loop?.pressesPerCheck).isNull()
    }
  }
}
