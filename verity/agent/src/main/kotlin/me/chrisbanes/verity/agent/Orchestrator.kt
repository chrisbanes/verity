package me.chrisbanes.verity.agent

import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.interaction.InteractionMapper
import me.chrisbanes.verity.core.journey.JourneySegmenter
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.InspectionVerdict
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneySegment
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.ArtifactError
import me.chrisbanes.verity.core.result.ArtifactErrorKind
import me.chrisbanes.verity.core.result.EvidenceArtifact
import me.chrisbanes.verity.core.result.EvidenceType
import me.chrisbanes.verity.core.result.SegmentExecutionMode
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
  private val artifactRecorder: JourneyArtifactRecorder = NoOpJourneyArtifactRecorder,
) {
  suspend fun run(journey: Journey): JourneyResult {
    // Launch the app before executing any segments.
    // Use bare `- launchApp` which reads appId from the flow header.
    session.executeFlow("appId: ${journey.app}\n---\n- launchApp")

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
    var executionMode = SegmentExecutionMode.ASSERTION_ONLY
    var actions = emptyList<String>()
    val generatedFlows = mutableListOf<String>()

    // Execute actions
    if (segment.actions.isNotEmpty()) {
      val instructions = segment.actions.map { it.instruction }
      actions = instructions
      if (isFastPath(instructions, platform)) {
        executeFastPath(instructions, appId, platform, navigator)
        executionMode = SegmentExecutionMode.FAST
      } else {
        val slowPathResult = executeSlowPath(
          instructions = instructions,
          appId = appId,
          platform = platform,
          navigator = navigator,
          segmentIndex = segment.index,
          label = "actions",
        )
        slowPathResult.reference?.let(generatedFlows::add)
        executionMode = SegmentExecutionMode.SLOW
        if (!slowPathResult.flowResult.success) {
          val message = "Flow execution failed: ${slowPathResult.flowResult.output}"
          return SegmentResult(
            index = segment.index,
            passed = false,
            reasoning = message,
            executionMode = executionMode,
            actions = actions,
            generatedFlows = generatedFlows,
            error = ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, message),
          )
        }
      }
    }

    // Execute loop
    segment.loop?.let { loop ->
      val loopResult = executeLoop(loop.action, loop.until, loop.max, appId, platform, navigator, segment.index)
      return SegmentResult(
        index = segment.index,
        passed = loopResult.satisfied,
        reasoning = loopResult.reasoning,
        executionMode = SegmentExecutionMode.LOOP,
        actions = listOf(loop.action),
        generatedFlows = loopResult.generatedFlows,
        error = if (loopResult.satisfied) {
          null
        } else {
          ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, loopResult.reasoning)
        },
      )
    }

    // Evaluate assertion
    segment.assertion?.let { assert ->
      val evaluation = evaluateAssertion(assert.description, assert.mode, inspector, segment.index)
      return SegmentResult(
        index = segment.index,
        passed = evaluation.verdict.passed,
        assertionMode = assert.mode,
        assertionDescription = assert.description,
        reasoning = evaluation.verdict.reasoning,
        executionMode = executionMode,
        actions = actions,
        generatedFlows = generatedFlows,
        evidence = evaluation.evidence,
        error = if (evaluation.verdict.passed) {
          null
        } else {
          ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, evaluation.verdict.reasoning)
        },
      )
    }

    // Actions only, no assertion — always passes
    return SegmentResult(
      index = segment.index,
      passed = true,
      executionMode = executionMode,
      actions = actions,
      generatedFlows = generatedFlows,
    )
  }

  private suspend fun executeFastPath(
    instructions: List<String>,
    appId: String,
    platform: Platform,
    navigator: NavigatorAgent,
  ) {
    val mapper = InteractionMapper.forPlatform(platform)
    for (instruction in instructions) {
      val interaction = checkNotNull(mapper.map(instruction)) {
        "Fast-path instruction '$instruction' did not map to an interaction for $platform"
      }
      executeWithScrollToFind(interaction, appId, navigator)
    }
  }

  private suspend fun executeWithScrollToFind(
    interaction: Interaction,
    appId: String,
    navigator: NavigatorAgent,
  ) {
    val executor = InteractionExecutor(session, appId)

    // For interactions that don't target a named element, just execute directly
    val targetText = when (interaction) {
      is Interaction.TapOnText -> interaction.text

      is Interaction.TapOnId -> interaction.resourceId

      is Interaction.LongPressOnText -> interaction.text

      else -> {
        executor.execute(interaction)
        return
      }
    }

    // Check if target is already on screen
    if (session.containsText(targetText)) {
      executor.execute(interaction)
      return
    }

    // Scroll-to-find loop (max 5 attempts)
    repeat(5) {
      val hierarchy = session.captureHierarchy()
      val direction = try {
        navigator.suggestScrollDirection(targetText, hierarchy)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // LLM call failed, stop scrolling and try executing anyway
        null
      } ?: return@repeat // LLM gave up or failed

      executor.execute(Interaction.Scroll(direction))

      if (session.containsText(targetText)) {
        executor.execute(interaction)
        return
      }
    }

    // Fall through: execute anyway (Maestro may find it via its own matching)
    executor.execute(interaction)
  }

  private suspend fun executeSlowPath(
    instructions: List<String>,
    appId: String,
    platform: Platform,
    navigator: NavigatorAgent,
    segmentIndex: Int,
    label: String,
  ): SlowPathResult {
    val yaml = navigator.generate(instructions, appId, platform, context)
    val reference = try {
      artifactRecorder.saveGeneratedFlow(segmentIndex, label, yaml)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    return SlowPathResult(session.executeFlow(yaml), reference)
  }

  private suspend fun executeLoop(
    action: String,
    until: String,
    max: Int,
    appId: String,
    platform: Platform,
    navigator: NavigatorAgent,
    segmentIndex: Int,
  ): LoopResult {
    val mapper = InteractionMapper.forPlatform(platform)
    val executor = InteractionExecutor(session, appId)
    val interaction = mapper.map(action)
    var actionsExecuted = 0
    val generatedFlows = mutableListOf<String>()

    repeat(max) {
      if (session.containsText(until)) {
        return LoopResult(
          satisfied = true,
          iterations = actionsExecuted,
          reasoning = "Text '$until' found after $actionsExecuted iterations",
          generatedFlows = generatedFlows,
        )
      }

      if (interaction != null) {
        executor.execute(interaction)
        actionsExecuted += 1
      } else {
        val label = "loop-${actionsExecuted.toString().padStart(3, '0')}"
        val slowPathResult = executeSlowPath(listOf(action), appId, platform, navigator, segmentIndex, label)
        slowPathResult.reference?.let(generatedFlows::add)
        if (!slowPathResult.flowResult.success) {
          return LoopResult(
            satisfied = false,
            iterations = actionsExecuted,
            reasoning = "Loop flow execution failed: ${slowPathResult.flowResult.output}",
            generatedFlows = generatedFlows,
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
        generatedFlows = generatedFlows,
      )
    }

    return LoopResult(
      satisfied = false,
      iterations = actionsExecuted,
      reasoning = "Text '$until' not found after $actionsExecuted iterations",
      generatedFlows = generatedFlows,
    )
  }

  private suspend fun evaluateAssertion(
    description: String,
    mode: AssertMode,
    inspector: InspectorAgent,
    segmentIndex: Int,
  ): AssertionEvaluation = when (mode) {
    AssertMode.VISIBLE -> {
      val passed = session.containsText(description)
      AssertionEvaluation(
        InspectionVerdict(
          passed = passed,
          reasoning = if (passed) "Text '$description' is visible" else "Text '$description' is not visible",
        ),
      )
    }

    AssertMode.FOCUSED -> {
      val passed = session.checkFocused(description)
      AssertionEvaluation(
        InspectionVerdict(
          passed = passed,
          reasoning = if (passed) "Text '$description' is focused" else "Text '$description' is not focused",
        ),
      )
    }

    AssertMode.TREE -> {
      val hierarchy = session.captureHierarchy(HierarchyFilter.CONTENT)
      val reference = try {
        artifactRecorder.saveHierarchy(segmentIndex, hierarchy)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
      AssertionEvaluation(
        verdict = inspector.evaluateTree(hierarchy, description),
        evidence = reference?.let { listOf(EvidenceArtifact(EvidenceType.HIERARCHY, it)) } ?: emptyList(),
      )
    }

    AssertMode.VISUAL -> {
      val artifact = try {
        artifactRecorder.screenshotPath(segmentIndex)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
      if (artifact != null) {
        val captured = try {
          session.captureScreenshot(artifact.path)
          true
        } catch (e: CancellationException) {
          throw e
        } catch (_: IOException) {
          false
        } catch (_: SecurityException) {
          false
        }
        if (captured) {
          AssertionEvaluation(
            verdict = inspector.evaluateVisual(artifact.path, description),
            evidence = listOf(EvidenceArtifact(EvidenceType.SCREENSHOT, artifact.relativePath)),
          )
        } else {
          evaluateVisualWithTempFile(inspector, description)
        }
      } else {
        evaluateVisualWithTempFile(inspector, description)
      }
    }
  }

  private suspend fun evaluateVisualWithTempFile(
    inspector: InspectorAgent,
    description: String,
  ): AssertionEvaluation {
    val tempFile = withContext(Dispatchers.IO) {
      Files.createTempFile("verity-screenshot-", ".png")
    }
    try {
      session.captureScreenshot(tempFile)
      return AssertionEvaluation(inspector.evaluateVisual(tempFile, description))
    } finally {
      withContext(NonCancellable + Dispatchers.IO) {
        Files.deleteIfExists(tempFile)
      }
    }
  }

  private data class SlowPathResult(
    val flowResult: FlowResult,
    val reference: String?,
  )

  private data class AssertionEvaluation(
    val verdict: InspectionVerdict,
    val evidence: List<EvidenceArtifact> = emptyList(),
  )

  companion object {
    fun isFastPath(instructions: List<String>, platform: Platform): Boolean {
      val mapper = InteractionMapper.forPlatform(platform)
      return mapper.allMappable(instructions)
    }
  }
}
