package me.chrisbanes.verity.agent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.core.hierarchy.FocusDetector
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.hierarchy.containsText
import me.chrisbanes.verity.core.model.InspectionVerdict
import me.chrisbanes.verity.core.parser.FocusConditionParser
import me.chrisbanes.verity.core.parser.LiteralConditionParser
import me.chrisbanes.verity.core.result.ConditionTier
import me.chrisbanes.verity.core.result.EvidenceArtifact
import me.chrisbanes.verity.core.result.EvidenceType
import me.chrisbanes.verity.core.result.PrecheckArtifact
import me.chrisbanes.verity.core.result.PrecheckOutcome
import me.chrisbanes.verity.device.DeviceSession

/** Exactly one check: no actions, polling, delay or accumulated journey state. */
class ConditionEvaluator(
  private val session: DeviceSession,
  private val inspector: InspectorAgent,
  private val artifactRecorder: JourneyArtifactRecorder = NoOpJourneyArtifactRecorder,
  private val segmentIndex: Int = 0,
  private val temporaryScreenshot: suspend (suspend (Path) -> InspectionVerdict) -> InspectionVerdict = ::withTemporaryScreenshot,
  private val verifyScreenshot: suspend (Path) -> Unit = ::requireScreenshot,
  private val onInspectedScreenshot: suspend (Path) -> Unit = {},
  /** Opt-in fast classifier consulted for tree-tier conditions before the inspector; null leaves every path unchanged. */
  private val precheck: ConditionPrecheck? = null,
  /** Opt-in fast classifier consulted for visual conditions on the captured screenshot before the inspector; null leaves every path unchanged. */
  private val visualPrecheck: ConditionPrecheck? = null,
) {
  /**
   * [loopHints] asks a tree-tier inspector for the optional batching fields; see [InspectorAgent.LOOP_HINTS].
   * A [tree] already settled after the preceding action answers the non-visual tiers instead of a fresh capture.
   */
  suspend fun evaluate(condition: String, context: InspectionContext = InspectionContext(), loopHints: Boolean = false, tree: HierarchyNode? = null): ConditionEvaluation {
    require(condition.isNotBlank()) { "Condition must not be blank" }
    val inspection = ScreenInspection(session, inspector, artifactRecorder, segmentIndex, temporaryScreenshot = temporaryScreenshot, verifyScreenshot = verifyScreenshot, onInspectedScreenshot = onInspectedScreenshot, visualPrecheck = visualPrecheck)
    val captureTree: suspend () -> HierarchyNode = if (tree != null) ({ tree }) else session::captureHierarchyTree
    return evaluateOn(condition, context, inspection, captureTree, loopHints) {}
  }

  internal suspend fun evaluate(condition: String, context: InspectionContext, deadline: EvaluationDeadline, checkIndex: Int): ConditionEvaluation {
    deadline.checkpoint()
    require(condition.isNotBlank()) { "Condition must not be blank" }
    val inspection = ScreenInspection(session, inspector, artifactRecorder.forWaitCheck(checkIndex), segmentIndex, deadline, temporaryScreenshot, verifyScreenshot, onInspectedScreenshot, visualPrecheck)
    return evaluateOn(condition, context, inspection, { session.captureHierarchyTree(deadline.remaining()) }, loopHints = false, deadline::checkpoint)
  }

  /** One hierarchy capture answers the literal, focus and tree tiers; a quoted condition never leaves the literal tier. */
  private suspend fun evaluateOn(
    condition: String,
    context: InspectionContext,
    inspection: ScreenInspection,
    captureTree: suspend () -> HierarchyNode,
    loopHints: Boolean,
    checkpoint: () -> Unit,
  ): ConditionEvaluation {
    visualDescription(condition)?.let { description ->
      val evaluation = inspection.visual(description, context)
      // A pre-check pass the inspector confirmed or overruled was decided by the inspector.
      val precheck = evaluation.precheck
      val tier = if (precheck != null && precheck.outcome == null) ConditionTier.PRECHECK else ConditionTier.VISUAL
      return ConditionEvaluation(evaluation.verdict, tier, evaluation.evidence, precheck = precheck)
    }
    val literalOnly = LiteralConditionParser.parse(condition)
    val tree = captureTree()
    checkpoint()
    if (literalOnly != null) return literalEvaluation(literalOnly, tree.containsText(literalOnly, checkpoint = checkpoint), tree)
    if (tree.containsText(condition, checkpoint = checkpoint)) return literalEvaluation(condition, passed = true, tree)
    FocusConditionParser.parse(condition)?.let { target ->
      return focusEvaluation(target, FocusDetector.containsFocused(tree, target, checkpoint), tree)
    }
    val hierarchy = HierarchyRenderer.render(tree, HierarchyFilter.PROMPT, checkpoint)
    precheck?.let { inspection.precheck(it, hierarchy, condition, context, loopHints) }?.let { return it.copy(tree = tree) }
    val evaluation = inspection.inspectTree(hierarchy, condition, context, loopHints)
    return ConditionEvaluation(evaluation.verdict, ConditionTier.TREE, evaluation.evidence, tree)
  }

  private fun literalEvaluation(text: String, passed: Boolean, tree: HierarchyNode) = ConditionEvaluation(
    InspectionVerdict(passed, if (passed) "Text '$text' is visible" else "Text '$text' is not visible"),
    ConditionTier.LITERAL,
    tree = tree,
  )

  private fun focusEvaluation(target: String, passed: Boolean, tree: HierarchyNode) = ConditionEvaluation(
    InspectionVerdict(passed, if (passed) "Text '$target' is focused" else "Text '$target' is not focused"),
    ConditionTier.FOCUS,
    tree = tree,
  )

  companion object {
    private val VISUAL_PREFIX = Regex("""^visually(?:\s+|$)""", RegexOption.IGNORE_CASE)

    /** The description after a `visually` prefix, or null for a non-visual condition. */
    private fun visualDescription(condition: String): String? = VISUAL_PREFIX.find(condition.trim())?.let { prefix ->
      condition.trim().substring(prefix.range.last + 1).trim().also {
        require(it.isNotBlank()) { "Visual condition must not be blank" }
      }
    }

    /** Whether [condition] is evaluated against a screenshot rather than the tree. */
    fun isVisual(condition: String): Boolean = VISUAL_PREFIX.containsMatchIn(condition.trim())

    /** Which form [evaluate] treats [condition] as, in its branch order. */
    fun kind(condition: String): ConditionKind = when {
      isVisual(condition) -> ConditionKind.VISUAL
      LiteralConditionParser.parse(condition) != null -> ConditionKind.LITERAL_ONLY
      FocusConditionParser.parse(condition) != null -> ConditionKind.FOCUS
      else -> ConditionKind.PROSE
    }

    /** Whether a loop may batch presses for [condition]: only tree-tier checks predict, so only prose conditions do. */
    fun canBatchPresses(condition: String): Boolean = kind(condition) == ConditionKind.PROSE
  }
}

/** How a condition is checked: [LITERAL_ONLY] is a wholly quoted condition, and only [PROSE] can reach a model through the tree tier. */
enum class ConditionKind { VISUAL, LITERAL_ONLY, FOCUS, PROSE }

data class ConditionEvaluation(
  val verdict: InspectionVerdict,
  val tier: ConditionTier,
  val evidence: List<EvidenceArtifact> = emptyList(),
  /** The tree this check read; null for the visual tier. */
  val tree: HierarchyNode? = null,
  /** Set when a pre-check answered; [PrecheckArtifact.outcome] is set when the inspector then confirmed or overruled its pass. */
  val precheck: PrecheckArtifact? = null,
)

internal data class InspectionEvaluation(
  val verdict: InspectionVerdict,
  val evidence: List<EvidenceArtifact> = emptyList(),
  /** Set when a visual pre-check answered; [PrecheckArtifact.outcome] is set when the inspector confirmed or overruled its pass. */
  val precheck: PrecheckArtifact? = null,
)

/** Current-state capture and optional artifact persistence shared by assertions and conditions. */
internal class ScreenInspection(
  private val session: DeviceSession,
  private val inspector: InspectorAgent,
  private val artifactRecorder: JourneyArtifactRecorder,
  private val segmentIndex: Int,
  private val deadline: EvaluationDeadline? = null,
  private val temporaryScreenshot: suspend (suspend (Path) -> InspectionVerdict) -> InspectionVerdict = ::withTemporaryScreenshot,
  private val verifyScreenshot: suspend (Path) -> Unit = ::requireScreenshot,
  /** Called with the inspected screenshot while it still exists, after the verdict and any deadline checkpoint. */
  private val onInspectedScreenshot: suspend (Path) -> Unit = {},
  /** Consulted on the captured screenshot in [visual] before the inspector; standalone assertions never pass one. */
  private val visualPrecheck: ConditionPrecheck? = null,
) {
  suspend fun tree(description: String, context: InspectionContext = InspectionContext()): InspectionEvaluation {
    val hierarchy = session.captureHierarchy(HierarchyFilter.PROMPT)
    return inspectTree(hierarchy, description, context)
  }

  suspend fun inspectTree(hierarchy: String, description: String, context: InspectionContext, loopHints: Boolean = false): InspectionEvaluation {
    deadline?.checkpoint()
    val evidence = saveHierarchyEvidence(hierarchy)
    deadline?.checkpoint()
    return InspectionEvaluation(inspector.evaluateTree(hierarchy, description, context, deadline?.onModelFailure, loopHints), evidence)
  }

  /** A confident pre-check verdict, or null to fall back to the inspector. Never throws except for cancellation and the deadline. */
  suspend fun precheck(precheck: ConditionPrecheck, hierarchy: String, description: String, context: InspectionContext, loopHints: Boolean): ConditionEvaluation? {
    // A loop that wants batching hints needs the inspector's verdict for an unmet check, so only a pass may be taken early.
    val verdict = ask(precheck, PrecheckState(session.platform, hierarchy, context.referenceText), description, acceptFail = !loopHints) ?: return null
    if (!(verdict.passed && precheck.confirmsPass)) {
      return ConditionEvaluation(verdict.toInspectionVerdict(), ConditionTier.PRECHECK, saveHierarchyEvidence(hierarchy), precheck = verdict.toArtifact())
    }
    // The ordinary inspector check on the same rendering; a failure, deadline or cancellation propagates and the pass is never a fallback.
    val inspected = inspectTree(hierarchy, description, context, loopHints)
    val passed = inspected.verdict.passed
    precheck.confirmation(verdict, passed)
    return ConditionEvaluation(inspected.verdict, ConditionTier.TREE, inspected.evidence, precheck = verdict.toArtifact(outcome(passed)))
  }

  private suspend fun ask(precheck: ConditionPrecheck, state: PrecheckState, description: String, acceptFail: Boolean): PrecheckVerdict? {
    deadline?.checkpoint()
    val verdict = try {
      precheck.check(state, description, acceptFail)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    deadline?.checkpoint()
    return verdict
  }

  /** The visual pre-check's confident verdict, or the inspector's: the same capture either way, never a recapture. */
  private suspend fun inspectScreenshot(screenshot: Path, description: String, context: InspectionContext): Pair<InspectionVerdict, PrecheckArtifact?> {
    val precheck = visualPrecheck
    val accepted = precheck?.let { ask(it, PrecheckState(session.platform, null, context.referenceText, screenshot = screenshot), description, acceptFail = true) }
    val inspect = suspend { inspector.evaluateVisual(screenshot, description, context, deadline?.onModelFailure) }
    if (precheck == null || accepted == null) return inspect() to null
    if (!(accepted.passed && precheck.confirmsPass)) return accepted.toInspectionVerdict() to accepted.toArtifact()
    // A failure, deadline or cancellation during confirmation propagates; the pass is never a fallback.
    val verdict = inspect()
    precheck.confirmation(accepted, verdict.passed)
    return verdict to accepted.toArtifact(outcome(verdict.passed))
  }

  private suspend fun saveHierarchyEvidence(hierarchy: String): List<EvidenceArtifact> {
    val reference = try {
      artifactRecorder.saveHierarchy(segmentIndex, hierarchy)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    return reference?.let { listOf(EvidenceArtifact(EvidenceType.HIERARCHY, it)) } ?: emptyList()
  }

  suspend fun visual(description: String, context: InspectionContext = InspectionContext()): InspectionEvaluation {
    deadline?.checkpoint()
    val artifact = try {
      artifactRecorder.screenshotPath(segmentIndex)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    deadline?.checkpoint()
    if (artifact != null) {
      val captured = try {
        captureScreenshot(artifact.path)
        true
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        if (deadline != null) throw e
        false
      } catch (e: SecurityException) {
        if (deadline != null) throw e
        false
      }
      if (captured) {
        verifyScreenshot(artifact.path)
        deadline?.checkpoint()
        val (verdict, precheck) = inspectScreenshot(artifact.path, description, context)
        deadline?.checkpoint()
        onInspectedScreenshot(artifact.path)
        return InspectionEvaluation(verdict, listOf(EvidenceArtifact(EvidenceType.SCREENSHOT, artifact.relativePath)), precheck)
      }
    }
    // The injected temporary-screenshot runner returns only a verdict, so the pre-check artifact leaves through this local.
    var precheck: PrecheckArtifact? = null
    val verdict = temporaryScreenshot { path ->
      deadline?.checkpoint()
      captureScreenshot(path)
      deadline?.checkpoint()
      verifyScreenshot(path)
      deadline?.checkpoint()
      val (verdict, accepted) = inspectScreenshot(path, description, context)
      precheck = accepted
      deadline?.checkpoint()
      onInspectedScreenshot(path)
      verdict
    }
    deadline?.checkpoint()
    return InspectionEvaluation(verdict, precheck = precheck)
  }

  private suspend fun captureScreenshot(path: Path) {
    if (deadline == null) session.captureScreenshot(path) else session.captureScreenshot(path, deadline.remaining())
  }
}

private fun PrecheckVerdict.toInspectionVerdict() = InspectionVerdict(passed, String.format(Locale.ROOT, "Pre-check probability %.2f (%s)", probability, model))

private fun PrecheckVerdict.toArtifact(outcome: PrecheckOutcome? = null) = PrecheckArtifact(probability, model, host, outcome)

private fun outcome(confirmed: Boolean) = if (confirmed) PrecheckOutcome.CONFIRMED else PrecheckOutcome.OVERRULED

private suspend fun withTemporaryScreenshot(inspect: suspend (Path) -> InspectionVerdict): InspectionVerdict = withContext(Dispatchers.IO) {
  val tempFile = Files.createTempFile("verity-screenshot-", ".png")
  try {
    inspect(tempFile)
  } finally {
    withContext(NonCancellable) { Files.deleteIfExists(tempFile) }
  }
}

private suspend fun requireScreenshot(path: Path) {
  check(withContext(Dispatchers.IO) { Files.isRegularFile(path) && Files.size(path) > 0 }) {
    "Screenshot capture produced no image"
  }
}
