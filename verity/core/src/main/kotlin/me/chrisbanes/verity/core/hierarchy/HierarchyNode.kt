package me.chrisbanes.verity.core.hierarchy

/**
 * Platform-agnostic representation of an accessibility tree node.
 * The device layer converts platform-specific trees into this format.
 */
data class HierarchyNode(
  val attributes: Map<String, String> = emptyMap(),
  val states: Set<String> = emptySet(),
  val children: List<HierarchyNode> = emptyList(),
)

/** Recursively checks whether any node in this tree has an attribute value containing [text]. */
fun HierarchyNode.containsText(text: String, ignoreCase: Boolean = true): Boolean {
  val match = attributes.values.any { it.contains(text, ignoreCase) }
  return match || children.any { it.containsText(text, ignoreCase) }
}
