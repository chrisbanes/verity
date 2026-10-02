package me.chrisbanes.verity.agent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
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
) {
  suspend fun evaluate(condition: String, context: InspectionContext = InspectionContext()): ConditionEvaluation {
    VISUAL_PREFIX.find(condition.trim())?.let { prefix ->
      val description = condition.trim().substring(prefix.range.last + 1).trim()
      val evaluation = ScreenInspection(session, inspector, artifactRecorder, segmentIndex).visual(description, context)
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
    val evaluation = ScreenInspection(session, inspector, artifactRecorder, segmentIndex).tree(condition, context)
    return ConditionEvaluation(evaluation.verdict, ConditionTier.TREE, evaluation.evidence)
  }
  private companion object {
    val VISUAL_PREFIX = Regex("""^visually(?:\s+|$)""", RegexOption.IGNORE_CASE)
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
) {
  suspend fun tree(description: String, context: InspectionContext = InspectionContext()): InspectionEvaluation {
    val hierarchy = session.captureHierarchy(HierarchyFilter.CONTENT)
    val reference = try {
      artifactRecorder.saveHierarchy(segmentIndex, hierarchy)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    return InspectionEvaluation(
      inspector.evaluateTree(hierarchy, description, context),
      reference?.let { listOf(EvidenceArtifact(EvidenceType.HIERARCHY, it)) } ?: emptyList(),
    )
  }

  suspend fun visual(description: String, context: InspectionContext = InspectionContext()): InspectionEvaluation {
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
        requireScreenshot(artifact.path)
        return InspectionEvaluation(
          inspector.evaluateVisual(artifact.path, description, context),
          listOf(EvidenceArtifact(EvidenceType.SCREENSHOT, artifact.relativePath)),
        )
      }
    }
    return withContext(Dispatchers.IO) {
      val tempFile = Files.createTempFile("verity-screenshot-", ".png")
      try {
        session.captureScreenshot(tempFile)
        requireScreenshot(tempFile)
        InspectionEvaluation(inspector.evaluateVisual(tempFile, description, context))
      } finally {
        withContext(NonCancellable) { Files.deleteIfExists(tempFile) }
      }
    }
  }

  private suspend fun requireScreenshot(path: Path) {
    check(withContext(Dispatchers.IO) { Files.isRegularFile(path) && Files.size(path) > 0 }) {
      "Screenshot capture produced no image"
    }
  }
}
