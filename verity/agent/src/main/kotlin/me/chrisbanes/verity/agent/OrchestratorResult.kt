package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.model.AssertMode

data class SegmentResult(
  val index: Int,
  val passed: Boolean,
  val assertionMode: AssertMode? = null,
  val reasoning: String = "",
)

data class JourneyResult(
  val journeyName: String,
  val segments: List<SegmentResult>,
) {
  val passed: Boolean get() = segments.all { it.passed }
  val failedAt: Int? get() = segments.firstOrNull { !it.passed }?.index
}

data class LoopResult(
  val satisfied: Boolean,
  val iterations: Int,
  val reasoning: String = "",
)
