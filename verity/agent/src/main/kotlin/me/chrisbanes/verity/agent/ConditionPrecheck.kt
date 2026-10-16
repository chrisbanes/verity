package me.chrisbanes.verity.agent

/**
 * A fast classifier consulted before the inspector for prose loop and wait conditions.
 * Returns null to fall back to the inspector; implementations must not throw except for cancellation.
 * When [acceptFail] is false the caller will discard a failing verdict, so implementations should treat it as unsure.
 */
fun interface ConditionPrecheck {
  suspend fun check(state: String, condition: String, acceptFail: Boolean): PrecheckVerdict?
}

data class PrecheckVerdict(
  val passed: Boolean,
  val probability: Double,
  val model: String,
  val host: String,
)

/** Mirrors the inspector's tree message without the assertion line, so the classifier sees the same evidence. */
internal fun precheckState(hierarchy: String, context: InspectionContext): String = buildString {
  append("Accessibility tree:\n").append(hierarchy)
  if (context.referenceText.isNotEmpty()) {
    append("\n\nReference context (earlier observations, not proof of the current condition):\n").append(context.referenceText)
  }
}
