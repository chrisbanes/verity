package me.chrisbanes.verity.agent

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.journey.JourneySegmenter
import me.chrisbanes.verity.core.keymap.PlatformKeyMapper
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.InspectionVerdict
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneySegment
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession

/**
 * Runs journeys segment by segment using a subagent pattern.
 * Each segment is isolated from previous ones to keep context windows small
 * and prevent interference between unrelated steps.
 */
class Orchestrator(
  private val session: DeviceSession,
  private val navigatorFactory: () -> NavigatorAgent,
  private val inspectorFactory: () -> InspectorAgent,
  private val context: String = "",
) {
  suspend fun run(journey: Journey): JourneyResult {
    val segments = JourneySegmenter.segment(journey.steps)
    val results = mutableListOf<SegmentResult>()

    for (segment in segments) {
      // Create fresh subagents for this segment to ensure isolation
      val navigator = navigatorFactory()
      val inspector = inspectorFactory()

      val result = executeSegment(segment, journey.app, journey.platform, navigator, inspector)
      results.add(result)
      if (!result.passed) break
    }

    return JourneyResult(journeyName = journey.name, segments = results)
  }

  private suspend fun executeSegment(
    segment: JourneySegment,
    appId: String,
    platform: Platform,
    navigator: NavigatorAgent,
    inspector: InspectorAgent,
  ): SegmentResult {
    // Execute actions
    if (segment.actions.isNotEmpty()) {
      val instructions = segment.actions.map { it.instruction }
      if (isFastPath(instructions, platform)) {
        executeFastPath(instructions, platform)
      } else {
        val flowResult = executeSlowPath(instructions, appId, platform, navigator)
        if (!flowResult.success) {
          return SegmentResult(
            index = segment.index,
            passed = false,
            reasoning = "Flow execution failed: ${flowResult.output}",
          )
        }
      }
    }

    // Execute loop
    segment.loop?.let { loop ->
      val loopResult = executeLoop(loop.action, loop.until, loop.max, appId, platform, navigator)
      return SegmentResult(
        index = segment.index,
        passed = loopResult.satisfied,
        reasoning = loopResult.reasoning,
      )
    }

    // Evaluate assertion
    segment.assertion?.let { assert ->
      val verdict = evaluateAssertion(assert.description, assert.mode, inspector)
      return SegmentResult(
        index = segment.index,
        passed = verdict.passed,
        assertionMode = assert.mode,
        reasoning = verdict.reasoning,
      )
    }

    // Actions only, no assertion — always passes
    return SegmentResult(index = segment.index, passed = true)
  }

  private suspend fun executeFastPath(instructions: List<String>, platform: Platform) {
    val mapper = PlatformKeyMapper.forPlatform(platform)
    for (instruction in instructions) {
      val keyName = checkNotNull(mapper.map(instruction)) {
        "Fast-path instruction '$instruction' did not map to a key for $platform"
      }
      session.pressKey(keyName)
      session.waitForAnimationToEnd()
    }
  }

  private suspend fun executeSlowPath(
    instructions: List<String>,
    appId: String,
    platform: Platform,
    navigator: NavigatorAgent,
  ): FlowResult {
    val yaml = navigator.generate(instructions, appId, platform, context)
    return session.executeFlow(yaml)
  }

  private suspend fun executeLoop(
    action: String,
    until: String,
    max: Int,
    appId: String,
    platform: Platform,
    navigator: NavigatorAgent,
  ): LoopResult {
    val mapper = PlatformKeyMapper.forPlatform(platform)
    val keyName = mapper.map(action)
    var actionsExecuted = 0

    repeat(max) {
      // Check exit condition (deterministic first)
      if (session.containsText(until)) {
        return LoopResult(
          satisfied = true,
          iterations = actionsExecuted,
          reasoning = "Text '$until' found after $actionsExecuted iterations",
        )
      }

      // Execute action
      if (keyName != null) {
        session.pressKey(keyName)
        session.waitForAnimationToEnd()
        actionsExecuted += 1
      } else {
        val flowResult = executeSlowPath(listOf(action), appId, platform, navigator)
        if (!flowResult.success) {
          return LoopResult(
            satisfied = false,
            iterations = actionsExecuted,
            reasoning = "Loop flow execution failed: ${flowResult.output}",
          )
        }
        actionsExecuted += 1
      }
    }

    // Final check after max iterations
    if (session.containsText(until)) {
      return LoopResult(
        satisfied = true,
        iterations = actionsExecuted,
        reasoning = "Text '$until' found after $actionsExecuted iterations",
      )
    }

    return LoopResult(
      satisfied = false,
      iterations = actionsExecuted,
      reasoning = "Text '$until' not found after $actionsExecuted iterations",
    )
  }

  private suspend fun evaluateAssertion(
    description: String,
    mode: AssertMode,
    inspector: InspectorAgent,
  ): InspectionVerdict = when (mode) {
    AssertMode.VISIBLE -> {
      val passed = session.containsText(description)
      InspectionVerdict(
        passed = passed,
        reasoning = if (passed) "Text '$description' is visible" else "Text '$description' is not visible",
      )
    }

    AssertMode.FOCUSED -> {
      val passed = session.checkFocused(description)
      InspectionVerdict(
        passed = passed,
        reasoning = if (passed) "Text '$description' is focused" else "Text '$description' is not focused",
      )
    }

    AssertMode.TREE -> {
      val hierarchy = session.captureHierarchy(HierarchyFilter.CONTENT)
      inspector.evaluateTree(hierarchy, description)
    }

    AssertMode.VISUAL -> {
      val tempFile = withContext(Dispatchers.IO) {
        Files.createTempFile("verity-screenshot-", ".png")
      }
      try {
        session.captureScreenshot(tempFile)
        inspector.evaluateVisual(tempFile, description)
      } finally {
        withContext(NonCancellable + Dispatchers.IO) {
          Files.deleteIfExists(tempFile)
        }
      }
    }
  }

  companion object {
    fun isFastPath(instructions: List<String>, platform: Platform): Boolean {
      val mapper = PlatformKeyMapper.forPlatform(platform)
      return mapper.allMappable(instructions)
    }
  }
}
