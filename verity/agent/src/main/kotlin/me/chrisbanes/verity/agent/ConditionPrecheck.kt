package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.model.Platform

/**
 * A fast classifier consulted before the inspector for prose loop and wait conditions.
 * Returns null to fall back to the inspector; implementations must not throw except for cancellation.
 * When [acceptFail] is false the caller will discard a failing verdict, so implementations should treat it as unsure.
 */
fun interface ConditionPrecheck {
  suspend fun check(state: PrecheckState, condition: String, acceptFail: Boolean): PrecheckVerdict?
}

/** The evidence the inspector would get for a tree check, minus the assertion line; how it is phrased is up to the classifier. */
data class PrecheckState(
  val platform: Platform,
  val hierarchy: String,
  /** Earlier journey observations; reference only, not proof of the current condition. Empty when there are none. */
  val referenceText: String = "",
)

data class PrecheckVerdict(
  val passed: Boolean,
  val probability: Double,
  val model: String,
  val host: String,
)
