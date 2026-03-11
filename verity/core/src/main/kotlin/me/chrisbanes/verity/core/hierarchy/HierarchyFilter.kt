package me.chrisbanes.verity.core.hierarchy

enum class HierarchyFilter(val allowedKeys: Set<String>?) {
  FOCUS(setOf("text", "resource-id", "selected")),
  CONTENT(setOf("text", "accessibilityText", "resource-id", "bounds")),
  ALL(null), // null = allow all
}
