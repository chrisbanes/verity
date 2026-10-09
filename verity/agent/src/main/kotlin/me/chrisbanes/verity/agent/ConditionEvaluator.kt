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
  suspend fun evaluate(condition: String, context: InspectionContext = InspectionContext()): ConditionEvaluation {
    require(condition.isNotBlank()) { "Condition must not be blank" }
    VISUAL_PREFIX.find(condition.trim())?.let { prefix ->
      val description = condition.trim().substring(prefix.range.last + 1).trim()
      require(description.isNotBlank()) { "Visual condition must not be blank" }
      val evaluation = ScreenInspection(session, inspector, artifactRecorder, segmentIndex, temporaryScreenshot = temporaryScreenshot, verifyScreenshot = verifyScreenshot, onInspectedScreenshot = onInspectedScreenshot).visual(description, context)
      return ConditionEvaluation(evaluation.verdict, ConditionTier.VISUAL, evaluation.evidence)
    }
    if (session.containsText(condition)) {
      return ConditionEvaluation(InspectionVerdict(true, "Text '$condition' is visible"), ConditionTier.LITERAL)
    }
    FocusConditionParser.parse(condition)?.let { target ->
      val passed = session.checkFocused(target)
      return ConditionEvaluation(
        InspectionVerdict(passed, if (passed) "Text '$target' is focused" else "Text '$target' is not focused"),
        ConditionTier.FOCUS,
      )
    }
    val evaluation = ScreenInspection(session, inspector, artifactRecorder, segmentIndex, temporaryScreenshot = temporaryScreenshot, verifyScreenshot = verifyScreenshot, onInspectedScreenshot = onInspectedScreenshot).tree(condition, context)
    return ConditionEvaluation(evaluation.verdict, ConditionTier.TREE, evaluation.evidence)
  }
  internal suspend fun evaluate(condition: String, context: InspectionContext, deadline: EvaluationDeadline, checkIndex: Int): ConditionEvaluation {
    deadline.checkpoint()
    require(condition.isNotBlank()) { "Condition must not be blank" }
    val inspection = ScreenInspection(session, inspector, artifactRecorder.forWaitCheck(checkIndex), segmentIndex, deadline, temporaryScreenshot, verifyScreenshot, onInspectedScreenshot)
    VISUAL_PREFIX.find(condition.trim())?.let { prefix ->
      val description = condition.trim().substring(prefix.range.last + 1).trim()
      require(description.isNotBlank()) { "Visual condition must not be blank" }
      val evaluation = inspection.visual(description, context)
      return ConditionEvaluation(evaluation.verdict, ConditionTier.VISUAL, evaluation.evidence)
    }
    val tree = session.captureHierarchyTree(deadline.remaining())
    deadline.checkpoint()
    if (tree.containsText(condition, checkpoint = deadline::checkpoint)) {
      return ConditionEvaluation(InspectionVerdict(true, "Text '$condition' is visible"), ConditionTier.LITERAL)
    }
    FocusConditionParser.parse(condition)?.let { target ->
      val passed = FocusDetector.containsFocused(tree, target, deadline::checkpoint)
      return ConditionEvaluation(
        InspectionVerdict(passed, if (passed) "Text '$target' is focused" else "Text '$target' is not focused"),
        ConditionTier.FOCUS,
      )
    }
    val hierarchy = HierarchyRenderer.render(tree, HierarchyFilter.CONTENT, deadline::checkpoint)
    val evaluation = inspection.inspectTree(hierarchy, condition, context)
    return ConditionEvaluation(evaluation.verdict, ConditionTier.TREE, evaluation.evidence)
  }

  companion object {
    private val VISUAL_PREFIX = Regex("""^visually(?:\s+|$)""", RegexOption.IGNORE_CASE)

    /** Whether [condition] is evaluated against a screenshot rather than the tree. */
    internal fun isVisual(condition: String): Boolean = VISUAL_PREFIX.containsMatchIn(condition.trim())
  }
}

data class ConditionEvaluation(
  val verdict: InspectionVerdict,
  val tier: ConditionTier,
  val evidence: List<EvidenceArtifact> = emptyList(),
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

  suspend fun inspectTree(hierarchy: String, description: String, context: InspectionContext): InspectionEvaluation {
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
      inspector.evaluateTree(hierarchy, description, context, deadline?.onModelFailure),
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
