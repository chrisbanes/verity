package me.chrisbanes.verity.agent

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.chrisbanes.verity.core.flow.ActionFlowYamlRenderer
import me.chrisbanes.verity.core.hierarchy.FocusObservation
import me.chrisbanes.verity.core.hierarchy.FocusStepPlanner
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.hierarchy.NodeBounds
import me.chrisbanes.verity.core.hierarchy.bounds
import me.chrisbanes.verity.core.hierarchy.containsText
import me.chrisbanes.verity.core.hierarchy.hasFocusChanged
import me.chrisbanes.verity.core.hierarchy.observeFocus
import me.chrisbanes.verity.core.interaction.Direction
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
import me.chrisbanes.verity.core.model.VerdictTarget
import me.chrisbanes.verity.core.result.ArtifactError
import me.chrisbanes.verity.core.result.ArtifactErrorKind
import me.chrisbanes.verity.core.result.ConditionTier
import me.chrisbanes.verity.core.result.LoopArtifact
import me.chrisbanes.verity.core.result.SegmentExecutionMode
import me.chrisbanes.verity.core.result.TrailGranularity
import me.chrisbanes.verity.core.result.TrailOrigin
import me.chrisbanes.verity.core.result.WaitArtifact
import me.chrisbanes.verity.core.timing.NoOpPhaseTimer
import me.chrisbanes.verity.core.timing.PhaseTimer
import me.chrisbanes.verity.device.CaptureDeadlineExceededException
import me.chrisbanes.verity.device.CaptureOperation
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FocusChangeObserver
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
  private val timer: PhaseTimer = NoOpPhaseTimer,
  private val precheck: ConditionPrecheck? = null,
) {
  suspend fun run(journey: Journey): JourneyResult {
    // Launch the app before executing any segments; running them on the wrong screen proves nothing.
    val launch = timer.step("${journey.name} setup") {
      session.executeActions(ActionFlow(journey.app, listOf(Interaction.LaunchApp())))
    }
    if (!launch.success) {
      val message = "Failed to launch app '${journey.app}': ${launch.output.trim().ifEmpty { "no details reported" }}"
      return JourneyResult(journey.name, segments = emptyList(), setupError = ArtifactError(ArtifactErrorKind.SETUP_FAILURE, message))
    }

    val segments = JourneySegmenter.segment(journey.steps)
    val results = mutableListOf<SegmentResult>()
    // Run-local so nothing carries over between journeys, even on a reused Orchestrator.
    val memory = JourneyMemory(session, launched = true)

    try {
      for (segment in segments) {
        val result = timer.step("${journey.name} segment ${segment.index}") {
          // Create fresh subagents for this segment to ensure isolation
          val navigator = navigatorFactory()
          val inspector = inspectorFactory()

          try {
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
      val evaluator = ConditionEvaluator(session, inspector, artifactRecorder, segment.index, onInspectedScreenshot = memory::recordScreenshot, precheck = precheck)
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
        wait = WaitArtifact(wait.until, wait.timeoutSeconds, result.elapsedMs, result.checks, evaluation?.tier, evaluation?.verdict?.reasoning.orEmpty(), evaluation?.precheck),
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
      val interactions = InteractionMapper.forPlatform(platform).mapAll(instructions)
      if (interactions != null) {
        executeFastPath(instructions, interactions, appId, navigator, memory, TrailSource(segment.index))
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
        loop = LoopArtifact(loop.until, loopResult.iterations, checkNotNull(loopResult.tier), loopResult.conditionReasoning, loopResult.pressesPerCheck, loopResult.precheck),
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
    interactions: List<Interaction>,
    appId: String,
    navigator: NavigatorAgent,
    memory: JourneyMemory,
    source: TrailSource,
  ) {
    validateActionFlow(ActionFlow(appId, interactions), session)
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
    var tree = session.captureHierarchyTree()
    if (tree.containsText(targetText)) {
      record(interaction)
      return
    }

    // Scroll-to-find loop (max 5 attempts); the post-scroll capture answers both the visibility check and the next prompt.
    repeat(5) {
      val direction = navigator.suggestScrollDirection(targetText, HierarchyRenderer.render(tree, HierarchyFilter.PROMPT))
        ?: return@repeat // A valid NONE is an ordinary navigation outcome.

      record(Interaction.Scroll(direction), TrailOrigin.SCROLL_TO_FIND)

      tree = session.captureHierarchyTree()
      if (tree.containsText(targetText)) {
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
    val hierarchy = try {
      // Capture and render share one budget; render checkpoints observe caller cancellation and expiry.
      val started = System.nanoTime()
      val callerContext = currentCoroutineContext()
      val tree = session.captureHierarchyTree(HIERARCHY_CAPTURE_TIMEOUT)
      HierarchyRenderer.render(tree, HierarchyFilter.PROMPT) {
        callerContext.ensureActive()
        if (System.nanoTime() - started >= HIERARCHY_CAPTURE_TIMEOUT.inWholeNanoseconds) throw CaptureDeadlineExceededException(CaptureOperation.HIERARCHY)
      }
    } catch (e: CancellationException) {
      // Budget expiry is a CancellationException subclass; only a cancelled caller aborts the step.
      if (e !is CaptureDeadlineExceededException) throw e
      currentCoroutineContext().ensureActive()
      System.err.println("Warning: hierarchy capture for navigator timed out; generating without it")
      null
    } catch (e: Exception) {
      // Class and message only: never log the tree, which may contain app text.
      System.err.println("Warning: could not capture hierarchy for navigator (${e::class.simpleName}: ${e.message}); generating without it")
      null
    }
    val selected = navigator.generate(instructions, appId, platform, context, hierarchy)
    validateActionFlow(selected, session)
    val reference = try {
      artifactRecorder.saveGeneratedFlow(segmentIndex, label, ActionFlowYamlRenderer.render(selected))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    // Setup just launched the app, so a leading plain relaunch would only restart it.
    val first = selected.actions.firstOrNull()
    // The setup launch stands in for one generated launch only, so a repeated loop body still relaunches.
    val toRun = if (first is Interaction.LaunchApp && first.clearState != true && memory.claimFreshLaunch()) selected.copy(actions = selected.actions.drop(1)) else selected
    // Nothing ran, so nothing is recorded.
    if (toRun.actions.isEmpty()) return SlowPathResult(FlowResult(success = true), reference)
    // One flow entry even when raw keycodes split it; focus is observed only around the whole flow.
    val flowResult = memory.recordExecution(source, TrailGranularity.FLOW, instructions, outcome = { it.success }) { session.executeOrdered(toRun) }
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
    val fastPathInteractions = InteractionMapper.forPlatform(platform).mapAll(instructions)
    val visual = ConditionEvaluator.isVisual(loop.until)
    val direction = FocusStepPlanner.batchDirection(fastPathInteractions, platform)?.takeIf { ConditionEvaluator.canBatchPresses(loop.until) }
    val evaluator = ConditionEvaluator(session, inspector, artifactRecorder, segmentIndex, onInspectedScreenshot = memory::recordScreenshot, precheck = precheck)
    suspend fun check() = evaluator.evaluate(loop.until, memory.inspectionContext(visual), loopHints = direction != null)
    var evaluation = check()
    var completedBodies = 0
    val generatedFlows = mutableListOf<String>()
    val pressesPerCheck = if (direction != null) mutableListOf<Int>() else null

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
        pressesPerCheck = pressesPerCheck?.toList()?.takeIf { it.isNotEmpty() },
        precheck = evaluation.precheck,
      )
    }

    if (evaluation.verdict.passed) return result()
    if (direction != null && pressesPerCheck != null && fastPathInteractions != null) {
      val keyName = (fastPathInteractions.single() as Interaction.KeyPress).keyName
      val focusObserver = FocusChangeObserver()
      val seen = mutableListOf<FocusObservation>()
      // Cleared on the first edge, wrap or unmet on-screen prediction; every later body is checked.
      var batching = true
      validateActionFlow(ActionFlow(appId, fastPathInteractions), session)
      while (completedBodies < loop.max) {
        evaluation.tree?.let { seen += observeFocus(it) }
        val prediction = if (batching) predictPresses(direction, evaluation) else null
        val size = minOf(prediction?.presses ?: 1, loop.max - completedBodies)
        var pressed = 0
        try {
          var baseline: FocusObservation? = null
          while (pressed < size) {
            val after = memory.recordExecution(TrailSource(segmentIndex, TrailOrigin.LOOP, completedBodies), TrailGranularity.INTERACTION, instructions) {
              session.pressAndSettle(focusObserver, direction = true, baseline) { session.pressKey(keyName) }
            }
            pressed++
            completedBodies++
            // No change means an edge or a failed observation; returning to an earlier focus means a wrap.
            if (after == null || seen.any { !hasFocusChanged(it, after) }) {
              batching = false
              break
            }
            seen += after
            baseline = after
            // Arriving early means the focus order skipped a predicted step; pressing on would overshoot.
            if (prediction?.target != null && after.focusedNodes.any { it.path.isRelatedPath(prediction.target) }) break
          }
        } catch (e: InteractionExecutionFailure) {
          if (pressed > 0) pressesPerCheck += pressed
          return result(e.message.orEmpty())
        }
        pressesPerCheck += pressed
        evaluation = check()
        if (evaluation.verdict.passed) return result()
        if (prediction?.target != null) batching = false
      }
      return result()
    }
    repeat(loop.max) {
      try {
        val source = TrailSource(segmentIndex, TrailOrigin.LOOP, completedBodies)
        if (fastPathInteractions != null) {
          executeFastPath(instructions, fastPathInteractions, appId, navigator, memory, source)
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

  /** [target] is the on-screen target's node path, in [observeFocus] form, or null when off-screen. */
  private class BatchPrediction(val presses: Int, val target: String? = null)

  /** Equal paths, or one is an ancestor of the other (a focused card around a target label, say). */
  private fun String.isRelatedPath(other: String): Boolean {
    fun String.dir() = if (endsWith("/")) this else "$this/"
    return dir().startsWith(other.dir()) || other.dir().startsWith(dir())
  }

  /** Preorder path of the first node with [bounds], in the child-index form [observeFocus] reports. */
  private fun HierarchyNode.pathOf(bounds: NodeBounds, path: String = "/"): String? {
    if (bounds() == bounds) return path
    children.forEachIndexed { index, child -> child.pathOf(bounds, if (path == "/") "/$index" else "$path/$index")?.let { return it } }
    return null
  }

  /**
   * Presses toward the unmet condition from the check's tree, or null to press once. Only the tree
   * tier predicts: a focus-tier check needs no model, so checking after every press costs little and
   * never lets focus pass a target the focus order reaches early.
   */
  private fun predictPresses(direction: Direction, evaluation: ConditionEvaluation): BatchPrediction? {
    if (evaluation.tier != ConditionTier.TREE) return null
    val tree = evaluation.tree ?: return null
    val verdict = evaluation.verdict
    val byTarget = when (val target = verdict.target) {
      VerdictTarget.NotVisible -> FocusStepPlanner.stepsPastVisible(tree, direction)?.let { BatchPrediction(it) }

      is VerdictTarget.Bounds -> tree.pathOf(target.bounds)
        // A target around the current focus would count as reached on the first press.
        ?.takeUnless { path -> observeFocus(tree).focusedNodes.any { it.path.isRelatedPath(path) } }
        ?.let { path -> FocusStepPlanner.stepsToTarget(tree, direction, target.bounds)?.let { BatchPrediction(it, path) } }

      null -> null
    }
    return byTarget ?: verdict.estimatedIterations?.let { BatchPrediction(it) }
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
    /** Budget for the optional navigator hierarchy; expiry is treated like any capture failure. */
    private val HIERARCHY_CAPTURE_TIMEOUT = 5.seconds

    fun isFastPath(instructions: List<String>, platform: Platform): Boolean {
      val mapper = InteractionMapper.forPlatform(platform)
      return mapper.allMappable(instructions)
    }
  }
}
