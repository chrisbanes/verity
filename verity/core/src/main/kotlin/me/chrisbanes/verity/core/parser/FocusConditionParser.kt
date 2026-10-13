package me.chrisbanes.verity.core.parser

/** The deliberately small grammar for deterministic focus conditions. */
object FocusConditionParser {
  private val patterns = listOf(
    Regex("""^(.+?)\s+is\s+focused$""", RegexOption.IGNORE_CASE),
    Regex("""^(.+?)\s+has\s+focus$""", RegexOption.IGNORE_CASE),
    Regex("""^focus\s+is\s+on\s+(.+)$""", RegexOption.IGNORE_CASE),
  )

  /** A focus target and whether it was wrapped in matching quotes. */
  data class FocusTarget(val text: String, val quoted: Boolean)

  fun parse(condition: String): String? = parseTarget(condition)?.text

  fun parseTarget(condition: String): FocusTarget? {
    val target = patterns.firstNotNullOfOrNull { it.matchEntire(condition.trim())?.groupValues?.get(1) }
      ?.trim() ?: return null
    val quoted = target.length >= 2 && target.first() == target.last() && target.first() in "\"'"
    val text = if (quoted) target.substring(1, target.lastIndex).trim() else target
    return text.takeIf { it.isNotEmpty() }?.let { FocusTarget(it, quoted) }
  }
}
