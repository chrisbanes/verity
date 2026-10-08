package me.chrisbanes.verity.core.parser

import me.chrisbanes.verity.core.model.JourneyStep

/** Explicit action-less waits; malformed recognized limits are authoring errors. */
object WaitStepInferrer {
  private val PREFIX = Regex("""^wait\s+until\b\s*(.*)$""", RegexOption.IGNORE_CASE)
  private val LIMIT_START = Regex("""(?:^|\s+)up\s+to(?:\s+|$)""", RegexOption.IGNORE_CASE)
  private val LIMIT_INTENT = Regex("""^up\s+to(?:\s+[+-]?[0-9].*|\s+(?:.*\s+)?(?:(?:nano|micro|milli)?seconds?|minutes?|hours?|days?|weeks?|months?|years?|[numµμ]?s|secs?|mins?|hrs?)|\s*)$""", RegexOption.IGNORE_CASE)
  private val LIMIT = Regex("""up\s+to\s+([0-9]+)\s+seconds?""", RegexOption.IGNORE_CASE)

  fun infer(text: String): JourneyStep.Wait? {
    val match = PREFIX.matchEntire(text.trim().removeSuffix(".").trimEnd()) ?: return null
    val body = match.groupValues[1].trim()
    val limitStart = LIMIT_START.findAll(body).lastOrNull()?.takeIf {
      LIMIT_INTENT.matches(body.substring(it.range.first).trim())
    }
    val until = if (limitStart == null) body else body.take(limitStart.range.first).trim()
    require(until.isNotBlank()) { "Wait condition must not be blank" }
    val timeout = if (limitStart == null) {
      20
    } else {
      val limit = LIMIT.matchEntire(body.substring(limitStart.range.first).trim())
      require(limit != null) { "Wait timeout must be a positive integer number of seconds" }
      val seconds = limit.groupValues[1].toIntOrNull()
      require(seconds != null && seconds > 0) { "Wait timeout must be a positive integer fitting Int" }
      seconds
    }
    return JourneyStep.Wait(until, timeout)
  }
}
