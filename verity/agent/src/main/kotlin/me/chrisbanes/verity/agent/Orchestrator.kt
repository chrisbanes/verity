package me.chrisbanes.verity.agent

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import me.chrisbanes.verity.core.flow.ActionFlowYamlRenderer
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.interaction.InteractionMapper
import me.chrisbanes.verity.core.journey.JourneySegmenter
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.InspectionVerdict
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneySegment
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.ArtifactError
import me.chrisbanes.verity.core.result.ArtifactErrorKind
import me.chrisbanes.verity.core.result.ConditionTier
import me.chrisbanes.verity.core.result.LoopArtifact
import me.chrisbanes.verity.core.result.SegmentExecutionMode
import me.chrisbanes.verity.core.result.TrailGranularity
import me.chrisbanes.verity.core.result.TrailOrigin
import me.chrisbanes.verity.core.result.WaitArtifact
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.validateActionFlow

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
  private val nowNanos: () -> Long = System::nanoTime,
) {
  suspend fun run(journey: Journey): JourneyResult {
    // Launch the app before executing any segments.
    session.executeActions(ActionFlow(journey.app, listOf(Interaction.LaunchApp())))

    val segments = JourneySegmenter.segment(journey.steps)
    val results = mutableListOf<SegmentResult>()
    // Run-local so nothing carries over between journeys, even on a reused Orchestrator.
    val memory = JourneyMemory(session)

    try {
      for (segment in segments) {
        // Create fresh subagents for this segment to ensure isolation
        val navigator = navigatorFactory()
        val inspector = inspectorFactory()

        val result = try {
          executeSegment(segment, journey.app, journey.platform, navigator, inspector, memory)
        } catch (e: InteractionExecutionFailure) {
          val instructions = segment.actions.map { it.instruction }
          SegmentResult(
            index = segment.index,
            passed = false,
            reasoning = e.message.orEmpty(),
            executionMode = if (isFastPath(instructions, journey.platform)) SegmentExecutionMode.FAST else SegmentExecutionMode.SLOW,
            actions = instructions,
            error = ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, e.message.orEmpty()),
          )
        }
        results.add(result)
        if (!result.passed) break
      }
      return JourneyResult(journeyName = journey.name, segments = results, trail = memory.trail())
    } finally {
      memory.close()
    }
  }

  private suspend fun executeSegment(
    segment: JourneySegment,
    appId: String,
    platform: Platform,
    navigator: NavigatorAgent,
    inspector: InspectorAgent,
    memory: JourneyMemory,
  ): SegmentResult {
    segment.wait?.let { wait ->
      val evaluator = ConditionEvaluator(session, inspector, artifactRecorder, segment.index, onInspectedScreenshot = memory::recordScreenshot)
      // Every poll sees the wait's starting references, even though polls keep recording newer screenshots.
      val result = memory.withFrozenContext(ConditionEvaluator.isVisual(wait.until)) { context ->
        ConditionWaiter(evaluator, nowNanos).await(wait.until, wait.timeoutSeconds.seconds, context)
      }
      val evaluation = result.lastEvaluation
      if (evaluation != null) memory.recordVerdict("wait until: ${wait.until}", result.satisfied, evaluation.verdict.reasoning)
      val reasoning = if (result.satisfied) {
        evaluation?.verdict?.reasoning.orEmpty()
      } else {
        "Wait timed out after ${wait.timeoutSeconds} seconds: ${wait.until}"
      }
      return SegmentResult(
        index = segment.index,
        passed = result.satisfied,
        reasoning = reasoning,
        executionMode = SegmentExecutionMode.WAIT,
        evidence = evaluation?.evidence.orEmpty(),
        wait = WaitArtifact(wait.until, wait.timeoutSeconds, result.elapsedMs, result.checks, evaluation?.tier, evaluation?.verdict?.reasoning.orEmpty()),
        error = if (result.satisfied) null else ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, reasoning),
      )
    }

    var executionMode = SegmentExecutionMode.ASSERTION_ONLY
    var actions = emptyList<String>()
    val generatedFlows = mutableListOf<String>()

    // Execute actions
    if (segment.actions.isNotEmpty()) {
      val instructions = segment.actions.map { it.instruction }
      actions = instructions
      if (isFastPath(instructions, platform)) {
        executeFastPath(instructions, appId, platform, navigator, memory, TrailSource(segment.index))
        executionMode = SegmentExecutionMode.FAST
      } else {
        val slowPathResult = executeSlowPath(
          instructions = instructions,
          appId = appId,
          platform = platform,
          navigator = navigator,
          segmentIndex = segment.index,
          label = "actions",
          memory = memory,
          source = TrailSource(segment.index),
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
      val loopResult = executeLoop(loop, appId, platform, navigator, inspector, segment.index, memory)
      memory.recordVerdict("loop until: ${loop.until}", loopResult.satisfied, loopResult.conditionReasoning)
      return SegmentResult(
        index = segment.index,
        passed = loopResult.satisfied,
        reasoning = loopResult.reasoning,
        executionMode = SegmentExecutionMode.LOOP,
        actions = loop.actionInstructions,
        generatedFlows = loopResult.generatedFlows,
        evidence = loopResult.evidence,
        loop = LoopArtifact(loop.until, loopResult.iterations, checkNotNull(loopResult.tier), loopResult.conditionReasoning),
        error = if (loopResult.satisfied) {
          null
        } else {
          ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, loopResult.reasoning)
        },
      )
    }

    // Evaluate assertion
    segment.assertion?.let { assert ->
      val evaluation = evaluateAssertion(assert.description, assert.mode, inspector, segment.index, memory)
      memory.recordVerdict(assert.description, evaluation.verdict.passed, evaluation.verdict.reasoning)
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
    memory: JourneyMemory,
    source: TrailSource,
  ) {
    val mapper = InteractionMapper.forPlatform(platform)
    val interactions = instructions.map { instruction ->
      checkNotNull(mapper.map(instruction)) {
        "Fast-path instruction '$instruction' did not map to an interaction for $platform"
      }
    }
    validateActionFlow(ActionFlow(appId, interactions))
    // Mapping is one interaction per instruction, so the two lists align.
    interactions.forEachIndexed { index, interaction ->
      executeWithScrollToFind(interaction, instructions[index], appId, navigator, memory, source)
    }
  }

  private suspend fun executeWithScrollToFind(
    interaction: Interaction,
    instruction: String,
    appId: String,
    navigator: NavigatorAgent,
    memory: JourneyMemory,
    source: TrailSource,
  ) {
    val executor = InteractionExecutor(session, appId)

    suspend fun record(executed: Interaction, origin: TrailOrigin = source.origin) = memory.recordExecution<Unit>(source.copy(origin = origin), TrailGranularity.INTERACTION, listOf(instruction)) {
      executor.execute(executed)
    }

    // For interactions that don't target a named element, just execute directly
    val targetText = when (interaction) {
      is Interaction.TapOnText -> interaction.text

      is Interaction.TapOnId -> interaction.resourceId

      is Interaction.LongPressOnText -> interaction.text

      else -> {
        record(interaction)
        return
      }
    }

    // Check if target is already on screen
    if (session.containsText(targetText)) {
      record(interaction)
      return
    }

    // Scroll-to-find loop (max 5 attempts)
    repeat(5) {
      val hierarchy = session.captureHierarchy()
      val direction = navigator.suggestScrollDirection(targetText, hierarchy)
        ?: return@repeat // A valid NONE is an ordinary navigation outcome.

      record(Interaction.Scroll(direction), TrailOrigin.SCROLL_TO_FIND)

      if (session.containsText(targetText)) {
        record(interaction)
        return
      }
    }

    // Fall through: execute anyway (Maestro may find it via its own matching)
    record(interaction)
  }

  private suspend fun executeSlowPath(
    instructions: List<String>,
    appId: String,
    platform: Platform,
    navigator: NavigatorAgent,
    segmentIndex: Int,
    label: String,
    memory: JourneyMemory,
    source: TrailSource,
  ): SlowPathResult {
    val selected = navigator.generate(instructions, appId, platform, context)
    val reference = try {
      artifactRecorder.saveGeneratedFlow(segmentIndex, label, ActionFlowYamlRenderer.render(selected))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    // One device call, so one flow entry; focus is observed only around the whole flow.
    val flowResult = memory.recordExecution(source, TrailGranularity.FLOW, instructions, outcome = { it.success }) { session.executeActions(selected) }
    return SlowPathResult(flowResult, reference)
  }

  private suspend fun executeLoop(
    loop: JourneyStep.Loop,
    appId: String,
    platform: Platform,
    navigator: NavigatorAgent,
    inspector: InspectorAgent,
    segmentIndex: Int,
    memory: JourneyMemory,
  ): LoopResult {
    val instructions = loop.actionInstructions
    val fastPath = isFastPath(instructions, platform)
    val evaluator = ConditionEvaluator(session, inspector, artifactRecorder, segmentIndex, onInspectedScreenshot = memory::recordScreenshot)
    var evaluation = evaluator.evaluate(loop.until, memory.inspectionContext(ConditionEvaluator.isVisual(loop.until)))
    var completedBodies = 0
    val generatedFlows = mutableListOf<String>()

    fun result(executionFailure: String? = null): LoopResult {
      val satisfied = executionFailure == null && evaluation.verdict.passed
      val reasoning = executionFailure ?: if (evaluation.tier == ConditionTier.LITERAL && satisfied) {
        "Text '${loop.until}' found after $completedBodies iterations"
      } else {
        "Condition '${loop.until}' ${if (satisfied) "satisfied" else "not satisfied"} after $completedBodies iterations: ${evaluation.verdict.reasoning}"
      }
      return LoopResult(
        satisfied = satisfied,
        iterations = completedBodies,
        reasoning = reasoning,
        generatedFlows = generatedFlows.toList(),
        tier = evaluation.tier,
        conditionReasoning = evaluation.verdict.reasoning,
        evidence = evaluation.evidence,
      )
    }

    if (evaluation.verdict.passed) return result()
    repeat(loop.max) {
      try {
        val source = TrailSource(segmentIndex, TrailOrigin.LOOP, completedBodies)
        if (fastPath) {
          executeFastPath(instructions, appId, platform, navigator, memory, source)
        } else {
          val label = "loop-${completedBodies.toString().padStart(3, '0')}"
          val slowPathResult = executeSlowPath(instructions, appId, platform, navigator, segmentIndex, label, memory, source)
          slowPathResult.reference?.let(generatedFlows::add)
          if (!slowPathResult.flowResult.success) throw InteractionExecutionFailure(slowPathResult.flowResult)
        }
      } catch (e: InteractionExecutionFailure) {
        return result(e.message.orEmpty())
      }
      completedBodies++
      evaluation = evaluator.evaluate(loop.until, memory.inspectionContext(ConditionEvaluator.isVisual(loop.until)))
      if (evaluation.verdict.passed) return result()
    }
    return result()
  }

  private suspend fun evaluateAssertion(
    description: String,
    mode: AssertMode,
    inspector: InspectorAgent,
    segmentIndex: Int,
    memory: JourneyMemory,
  ): InspectionEvaluation = when (mode) {
    AssertMode.VISIBLE -> {
      val passed = session.containsText(description)
      InspectionEvaluation(
        InspectionVerdict(
          passed = passed,
          reasoning = if (passed) "Text '$description' is visible" else "Text '$description' is not visible",
        ),
      )
    }

    AssertMode.FOCUSED -> {
      val passed = session.checkFocused(description)
      InspectionEvaluation(
        InspectionVerdict(
          passed = passed,
          reasoning = if (passed) "Text '$description' is focused" else "Text '$description' is not focused",
        ),
      )
    }

    AssertMode.TREE -> ScreenInspection(session, inspector, artifactRecorder, segmentIndex).tree(description, memory.inspectionContext(includeScreenshots = false))

    AssertMode.VISUAL -> ScreenInspection(session, inspector, artifactRecorder, segmentIndex, onInspectedScreenshot = memory::recordScreenshot).visual(description, memory.inspectionContext(includeScreenshots = true))
  }

  private data class SlowPathResult(
    val flowResult: FlowResult,
    val reference: String?,
  )

  companion object {
    fun isFastPath(instructions: List<String>, platform: Platform): Boolean {
      val mapper = InteractionMapper.forPlatform(platform)
      return mapper.allMappable(instructions)
    }
  }
}
