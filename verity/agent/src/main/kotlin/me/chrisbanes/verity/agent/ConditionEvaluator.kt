package me.chrisbanes.verity.agent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
import me.chrisbanes.verity.core.result.ConditionTier
import me.chrisbanes.verity.core.result.EvidenceArtifact
import me.chrisbanes.verity.core.result.EvidenceType
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
) {
  /** [loopHints] asks a tree-tier inspector for the optional batching fields; see [InspectorAgent.LOOP_HINTS]. */
  suspend fun evaluate(condition: String, context: InspectionContext = InspectionContext(), loopHints: Boolean = false): ConditionEvaluation {
    require(condition.isNotBlank()) { "Condition must not be blank" }
    val inspection = ScreenInspection(session, inspector, artifactRecorder, segmentIndex, temporaryScreenshot = temporaryScreenshot, verifyScreenshot = verifyScreenshot, onInspectedScreenshot = onInspectedScreenshot)
    return evaluateOn(condition, context, inspection, session::captureHierarchyTree, loopHints) {}
  }

  internal suspend fun evaluate(condition: String, context: InspectionContext, deadline: EvaluationDeadline, checkIndex: Int): ConditionEvaluation {
    deadline.checkpoint()
    require(condition.isNotBlank()) { "Condition must not be blank" }
    val inspection = ScreenInspection(session, inspector, artifactRecorder.forWaitCheck(checkIndex), segmentIndex, deadline, temporaryScreenshot, verifyScreenshot, onInspectedScreenshot)
    return evaluateOn(condition, context, inspection, { session.captureHierarchyTree(deadline.remaining()) }, loopHints = false, deadline::checkpoint)
  }

  /** One hierarchy capture answers the literal, focus and tree tiers. */
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
      return ConditionEvaluation(evaluation.verdict, ConditionTier.VISUAL, evaluation.evidence)
    }
    val tree = captureTree()
    checkpoint()
    if (tree.containsText(condition, checkpoint = checkpoint)) return literalEvaluation(condition, tree)
    FocusConditionParser.parse(condition)?.let { target ->
      return focusEvaluation(target, FocusDetector.containsFocused(tree, target, checkpoint), tree)
    }
    val hierarchy = HierarchyRenderer.render(tree, HierarchyFilter.CONTENT, checkpoint)
    val evaluation = inspection.inspectTree(hierarchy, condition, context, loopHints)
    return ConditionEvaluation(evaluation.verdict, ConditionTier.TREE, evaluation.evidence, tree)
  }

  private fun literalEvaluation(text: String, tree: HierarchyNode) = ConditionEvaluation(InspectionVerdict(true, "Text '$text' is visible"), ConditionTier.LITERAL, tree = tree)

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

    /** Whether a loop may batch presses for [condition]: only tree-tier checks predict, so focus forms and `visually` never do. */
    fun canBatchPresses(condition: String): Boolean = !isVisual(condition) && FocusConditionParser.parse(condition) == null
  }
}

data class ConditionEvaluation(
  val verdict: InspectionVerdict,
  val tier: ConditionTier,
  val evidence: List<EvidenceArtifact> = emptyList(),
  /** The tree this check read; null for the visual tier. */
  val tree: HierarchyNode? = null,
)

internal data class InspectionEvaluation(
  val verdict: InspectionVerdict,
  val evidence: List<EvidenceArtifact> = emptyList(),
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
) {
  suspend fun tree(description: String, context: InspectionContext = InspectionContext()): InspectionEvaluation {
    val hierarchy = session.captureHierarchy(HierarchyFilter.CONTENT)
    return inspectTree(hierarchy, description, context)
  }

  suspend fun inspectTree(hierarchy: String, description: String, context: InspectionContext, loopHints: Boolean = false): InspectionEvaluation {
    deadline?.checkpoint()
    val reference = try {
      artifactRecorder.saveHierarchy(segmentIndex, hierarchy)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    deadline?.checkpoint()
    return InspectionEvaluation(
      inspector.evaluateTree(hierarchy, description, context, deadline?.onModelFailure, loopHints),
      reference?.let { listOf(EvidenceArtifact(EvidenceType.HIERARCHY, it)) } ?: emptyList(),
    )
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
        val verdict = inspector.evaluateVisual(artifact.path, description, context, deadline?.onModelFailure)
        deadline?.checkpoint()
        onInspectedScreenshot(artifact.path)
        return InspectionEvaluation(verdict, listOf(EvidenceArtifact(EvidenceType.SCREENSHOT, artifact.relativePath)))
      }
    }
    val verdict = temporaryScreenshot { path ->
      deadline?.checkpoint()
      captureScreenshot(path)
      deadline?.checkpoint()
      verifyScreenshot(path)
      deadline?.checkpoint()
      val verdict = inspector.evaluateVisual(path, description, context, deadline?.onModelFailure)
      deadline?.checkpoint()
      onInspectedScreenshot(path)
      verdict
    }
    deadline?.checkpoint()
    return InspectionEvaluation(verdict)
  }

  private suspend fun captureScreenshot(path: Path) {
    if (deadline == null) session.captureScreenshot(path) else session.captureScreenshot(path, deadline.remaining())
  }
}

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
