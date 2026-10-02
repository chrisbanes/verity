package me.chrisbanes.verity.core.parser

/** The deliberately small grammar for deterministic focus conditions. */
object FocusConditionParser {
  private val patterns = listOf(
    Regex("""^(.+?)\s+is\s+focused$""", RegexOption.IGNORE_CASE),
    Regex("""^(.+?)\s+has\s+focus$""", RegexOption.IGNORE_CASE),
    Regex("""^focus\s+is\s+on\s+(.+)$""", RegexOption.IGNORE_CASE),
  )

  fun parse(condition: String): String? {
    val target = patterns.firstNotNullOfOrNull { it.matchEntire(condition.trim())?.groupValues?.get(1) }
      ?.trim() ?: return null
    val unquoted = if (target.length >= 2 && target.first() == target.last() && target.first() in "\"'") {
      target.substring(1, target.lastIndex).trim()
    } else {
      target
    }
    return unquoted.takeIf { it.isNotEmpty() }
  }
}
