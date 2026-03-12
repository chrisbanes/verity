package me.chrisbanes.verity.agent

import java.nio.file.Files
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.journey.JourneySegmenter
import me.chrisbanes.verity.core.keymap.PlatformKeyMapper
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.FlowResult
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
      val loopResult = executeLoop(loop.action, loop.until, loop.max, platform)
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
        passed = verdict,
        assertionMode = assert.mode,
        reasoning = "",
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
    platform: Platform,
  ): LoopResult {
    val mapper = PlatformKeyMapper.forPlatform(platform)
    val keyName = mapper.map(action)

    for (i in 1..max) {
      // Check exit condition (deterministic first)
      if (session.containsText(until)) {
        return LoopResult(satisfied = true, iterations = i, reasoning = "Text '$until' found")
      }

      // Execute action
      if (keyName != null) {
        session.pressKey(keyName)
        session.waitForAnimationToEnd()
      } else {
        return LoopResult(
          satisfied = false,
          iterations = i,
          reasoning = "LLM fallback for non-key-mapped loop action '$action' is not yet implemented",
        )
      }
    }

    // Final check after max iterations
    if (session.containsText(until)) {
      return LoopResult(
        satisfied = true,
        iterations = max,
        reasoning = "Text '$until' found after max iterations",
      )
    }

    return LoopResult(
      satisfied = false,
      iterations = max,
      reasoning = "Text '$until' not found after $max iterations",
    )
  }

  private suspend fun evaluateAssertion(
    description: String,
    mode: AssertMode,
    inspector: InspectorAgent,
  ): Boolean = when (mode) {
    AssertMode.VISIBLE -> session.containsText(description)

    AssertMode.FOCUSED -> session.checkFocused(description)

    AssertMode.TREE -> {
      val hierarchy = session.captureHierarchy(HierarchyFilter.CONTENT)
      inspector.evaluateTree(hierarchy, description).passed
    }

    AssertMode.VISUAL -> {
      val tempFile = Files.createTempFile("verity-screenshot-", ".png")
      try {
        session.captureScreenshot(tempFile)
        inspector.evaluateVisual(tempFile, description).passed
      } finally {
        Files.deleteIfExists(tempFile)
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
