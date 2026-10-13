package me.chrisbanes.verity.core.parser

import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep

/** Advisory authoring checks that never block a run. */
object JourneyWarnings {
  const val MAX_UNQUOTED_WORDS = 3
  const val MAX_TARGET_LENGTH = 40

  /**
   * Flags loop/wait conditions that route to the deterministic focus check but whose target looks
   * like prose, so it can never match and the loop runs to its limit.
   */
  fun check(journey: Journey, file: String): List<String> = journey.steps.mapIndexedNotNull { index, step ->
    val until = when (step) {
      is JourneyStep.Loop -> step.until
      is JourneyStep.Wait -> step.until
      else -> return@mapIndexedNotNull null
    }
    if (until.trim().startsWith("visually", ignoreCase = true)) return@mapIndexedNotNull null
    val target = FocusConditionParser.parseTarget(until) ?: return@mapIndexedNotNull null
    val prose = !target.quoted && target.text.split(Regex("\\s+")).size > MAX_UNQUOTED_WORDS
    if (!prose && target.text.length <= MAX_TARGET_LENGTH) return@mapIndexedNotNull null
    "Warning: $file step ${index + 1}: condition \"${until.trim()}\" is checked as a focus target " +
      "\"${target.text}\" and will not match prose. Quote the exact on-screen label, or rephrase " +
      "without \"is focused\" / \"has focus\" / \"focus is on\" so the inspector evaluates it."
  }
}
