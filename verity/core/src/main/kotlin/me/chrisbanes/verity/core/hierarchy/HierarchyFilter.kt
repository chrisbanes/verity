package me.chrisbanes.verity.core.hierarchy

/**
 * @param collapseLayoutOnly when true, `bounds` is treated as layout rather than content: nodes whose
 * only attribute is `bounds` (and have no states) are collapsed when they have 0 or 1 children, and an
 * `accessibilityText` equal to `text` is dropped. Every kept line renders as it does without this flag.
 */
enum class HierarchyFilter(val allowedKeys: Set<String>?, val collapseLayoutOnly: Boolean = false) {
  FOCUS(setOf("text", "resource-id", "selected")),
  CONTENT(setOf("text", "accessibilityText", "resource-id", "bounds")),
  PROMPT(setOf("text", "accessibilityText", "resource-id", "bounds"), collapseLayoutOnly = true),
  ALL(null), // null = allow all
}
