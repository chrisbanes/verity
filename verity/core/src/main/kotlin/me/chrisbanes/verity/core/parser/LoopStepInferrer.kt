package me.chrisbanes.verity.core.parser

import me.chrisbanes.verity.core.model.JourneyStep

object LoopStepInferrer {

  private val LOOP_VERBS = setOf("press", "navigate", "move", "scroll", "go", "step")

  // Limit suffixes are anchored so prose inside the condition is retained.
  private val PATTERN = Regex("""^(.+?)\s+until\s+(.+?)\s*$""", RegexOption.IGNORE_CASE)
  private val LIMIT = Regex(
    """\s+(?:up\s+to\s+(\d+)\s+times?|max\s+(\d+)|for\s+up\s+to\s+(\d+)|(\d+)\s+iterations)\s*$""",
    RegexOption.IGNORE_CASE,
  )

  fun infer(text: String): JourneyStep.Loop? {
    val match = PATTERN.matchEntire(text.trim().removeSuffix(".").trimEnd()) ?: return null
    val action = match.groupValues[1].trim()
    val condition = match.groupValues[2].trim()
    val limit = LIMIT.find(condition)
    val until = if (limit == null) condition else condition.take(limit.range.first).trim()
    if (until.isEmpty()) return null
    val max = limit?.groupValues?.drop(1)?.first { it.isNotEmpty() }?.toInt() ?: 20

    // First word of action must be an allowed verb
    val verb = action.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: return null
    if (verb !in LOOP_VERBS) return null

    return JourneyStep.Loop(action = action, until = until, max = max)
  }
}
