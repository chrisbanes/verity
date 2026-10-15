package me.chrisbanes.verity.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.chrisbanes.verity.core.hierarchy.NodeBounds

@Serializable
data class InspectionVerdict(
  val passed: Boolean,
  val reasoning: String,
  /** Optional loop hint: where the unmet condition's target is. Not part of any wire format. */
  @Transient val target: VerdictTarget? = null,
  /** Optional loop hint: a cautious count of bodies still needed. Not part of any wire format. */
  @Transient val estimatedIterations: Int? = null,
)

sealed interface VerdictTarget {
  data object NotVisible : VerdictTarget

  data class Bounds(val bounds: NodeBounds) : VerdictTarget
}
