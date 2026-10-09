package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.result.ArtifactError
import me.chrisbanes.verity.core.result.ConditionTier
import me.chrisbanes.verity.core.result.EvidenceArtifact
import me.chrisbanes.verity.core.result.JourneyTrailArtifact
import me.chrisbanes.verity.core.result.LoopArtifact
import me.chrisbanes.verity.core.result.SegmentExecutionMode
import me.chrisbanes.verity.core.result.WaitArtifact

data class SegmentResult(
  val index: Int,
  val passed: Boolean,
  val assertionMode: AssertMode? = null,
  val assertionDescription: String? = null,
  val reasoning: String = "",
  val executionMode: SegmentExecutionMode = SegmentExecutionMode.ASSERTION_ONLY,
  val actions: List<String> = emptyList(),
  val generatedFlows: List<String> = emptyList(),
  val evidence: List<EvidenceArtifact> = emptyList(),
  val error: ArtifactError? = null,
  val loop: LoopArtifact? = null,
  val wait: WaitArtifact? = null,
)

data class JourneyResult(
  val journeyName: String,
  val segments: List<SegmentResult>,
  val trail: JourneyTrailArtifact? = null,
) {
  val passed: Boolean get() = segments.all { it.passed }
  val failedAt: Int? get() = segments.firstOrNull { !it.passed }?.index
}

data class LoopResult(
  val satisfied: Boolean,
  val iterations: Int,
  val reasoning: String = "",
  val generatedFlows: List<String> = emptyList(),
  val tier: ConditionTier? = null,
  val conditionReasoning: String = "",
  val evidence: List<EvidenceArtifact> = emptyList(),
)
