package me.chrisbanes.verity.core.parser

/** A condition wholly wrapped in matching quotes is checked only as literal text, never by a model. */
object LiteralConditionParser {
  /** The trimmed text inside the quotes, or null when [condition] is not wholly quoted; blank quoted text is rejected. */
  fun parse(condition: String): String? {
    val trimmed = condition.trim()
    if (trimmed.length < 2) return null
    val quote = trimmed.first()
    if (quote !in "\"'" || trimmed.last() != quote) return null
    val inner = trimmed.substring(1, trimmed.lastIndex)
    // `"a" and "b"` is two quoted fragments, not one quoted condition.
    if (quote in inner) return null
    return inner.trim().also { require(it.isNotEmpty()) { "Literal condition must not be blank" } }
  }
}
