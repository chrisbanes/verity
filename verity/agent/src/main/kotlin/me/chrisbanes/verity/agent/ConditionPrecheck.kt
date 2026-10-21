package me.chrisbanes.verity.agent

import java.nio.file.Path
import me.chrisbanes.verity.core.model.Platform

/**
 * A fast classifier consulted before the inspector for prose loop and wait conditions, and for opted-in visual ones.
 * Returns null to fall back to the inspector; implementations must not throw except for cancellation.
 * When [acceptFail] is false the caller will discard a failing verdict, so implementations should treat it as unsure.
 */
fun interface ConditionPrecheck {
  suspend fun check(state: PrecheckState, condition: String, acceptFail: Boolean): PrecheckVerdict?
}

/**
 * The evidence the inspector would get for one check, minus the assertion line; how it is phrased is up to the classifier.
 * Exactly one of [hierarchy] (a tree check) and [screenshot] (a visual check) is set.
 */
data class PrecheckState(
  val platform: Platform,
  val hierarchy: String?,
  /** Earlier journey observations; reference only, not proof of the current condition. Empty when there are none. */
  val referenceText: String = "",
  /** The current screenshot, which exists only for the duration of [ConditionPrecheck.check]. */
  val screenshot: Path? = null,
) {
  init {
    require((hierarchy == null) != (screenshot == null)) { "PrecheckState needs exactly one of hierarchy and screenshot" }
  }
}

data class PrecheckVerdict(
  val passed: Boolean,
  val probability: Double,
  val model: String,
  val host: String,
)
