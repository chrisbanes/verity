package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.agent.ConditionEvaluator
import me.chrisbanes.verity.agent.ConditionWaiter
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.inspectionReply
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.result.ConditionTier
import me.chrisbanes.verity.device.DeviceSession

/** Ordinary configured-device functional proof; no claim about a native CPU phase deadline. */
internal suspend fun qualifyConditionWaits(production: DeviceSession, appId: String, label: String) {
  // Setup is outside the wait; the guarded session forbids every action during evaluation.
  check(production.executeActions(ActionFlow(appId, listOf(Interaction.LaunchApp()))).success)
  production.waitForAnimationToEnd()
  val initial = production.captureHierarchyTree(5.seconds)
  fun literal(node: HierarchyNode): String? = node.attributes.entries.firstOrNull {
    it.key in setOf("text", "label", "contentDescription", "value", "name") && it.value.isNotBlank()
  }?.value ?: node.children.firstNotNullOfOrNull(::literal)
  val visible = checkNotNull(literal(initial)) { "No literal text in the configured Settings hierarchy" }
  var active = 0
  var peak = 0
  var captures = 0
  val guarded = object : DeviceSession by production {
    override suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode {
      active++
      captures++
      peak = maxOf(peak, active)
      try {
        return production.captureHierarchyTree(timeout)
      } finally {
        active--
      }
    }
    override suspend fun captureScreenshot(output: Path, timeout: Duration) {
      active++
      captures++
      peak = maxOf(peak, active)
      try {
        production.captureScreenshot(output, timeout)
      } finally {
        active--
      }
    }
    override suspend fun captureHierarchyTree(): HierarchyNode = error("Unbounded wait capture")
    override suspend fun captureScreenshot(output: Path): Unit = error("Unbounded wait screenshot")
    override suspend fun executeActions(flow: ActionFlow): FlowResult = error("Wait performed an action")
    override suspend fun executeFlow(yaml: String): FlowResult = error("Wait performed a flow")
    override suspend fun pressKey(keyName: String): Unit = error("Wait pressed a key")
    override suspend fun pressKey(keyName: String, longPress: Boolean): Unit = error("Wait pressed a key")
    override suspend fun pressKey(keycode: Int, longPress: Boolean): Unit = error("Wait pressed a key")
    override suspend fun shell(command: String): String = error("Wait ran shell")
    override suspend fun waitForAnimationToEnd(): Unit = error("Wait animated")
  }
  val images = mutableListOf<Path>()
  var delayed = false
  var modelEntered = false
  var modelJoined = false
  val inspector = InspectorAgent(
    { _, _, _ -> error("Literal/focus wait unexpectedly invoked a model") },
    { _, _, path, _ ->
      images.add(path)
      assertThat(withContext(Dispatchers.IO) { Files.isRegularFile(path) && Files.size(path) > 0 }).isTrue()
      if (delayed) {
        modelEntered = true
        try {
          awaitCancellation()
        } finally {
          withContext(NonCancellable) {
            delay(50)
            modelJoined = true
          }
        }
      } else {
        inspectionReply("""{"passed":true,"reasoning":"Current native screenshot is present"}""")
      }
    },
  )
  val waiter = ConditionWaiter(ConditionEvaluator(guarded, inspector))
  val started = System.nanoTime()
  val immediate = waiter.await(visible, 5.seconds)
  assertThat(immediate.satisfied).isTrue()
  assertThat(immediate.lastEvaluation?.tier).isEqualTo(ConditionTier.LITERAL)
  val absent = "focus is on '__verity_absent_${UUID.randomUUID()}__'"
  val negative = waiter.await(absent, 2.seconds)
  assertThat(negative.satisfied).isFalse()
  assertThat(negative.lastEvaluation?.tier).isEqualTo(ConditionTier.FOCUS)
  assertThat(negative.checks > 0).isTrue()
  val visual = waiter.await("visually native screenshot is available", 5.seconds)
  assertThat(visual.satisfied).isTrue()
  assertThat(visual.lastEvaluation?.tier).isEqualTo(ConditionTier.VISUAL)
  delayed = true
  val cancelled = waiter.await("visually delayed negative condition", 5.seconds)
  assertThat(cancelled.satisfied).isFalse()
  assertThat(cancelled.checks).isEqualTo(0)
  assertThat(modelEntered && modelJoined).isTrue()
  assertThat(active).isEqualTo(0)
  assertThat(peak).isEqualTo(1)
  assertThat(images.size).isEqualTo(2)
  withContext(Dispatchers.IO) { images.forEach { assertThat(Files.exists(it)).isFalse() } }
  val output = withContext(Dispatchers.IO) { Files.createTempFile("verity-wait-reuse", ".png") }
  try {
    production.captureHierarchyTree(2.seconds)
    production.captureHierarchyTree()
    production.captureScreenshot(output, 2.seconds)
    production.captureScreenshot(output)
    assertThat(withContext(Dispatchers.IO) { Files.size(output) > 0 }).isTrue()
  } finally {
    withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(output) }
  }
  println("$label wait_functional_literal_focus_visual=true capture_count=$captures peak_capture=1 active_after_join=0 model_backend_calls=0 delayed_fake_model_joined=true owned_images_removed=true same_session_bounded_noarg_reuse=true entry_ns=$started complete_ns=${System.nanoTime()} head=${System.getenv("GITHUB_SHA")} run=${System.getenv("GITHUB_RUN_ID")}")
}
