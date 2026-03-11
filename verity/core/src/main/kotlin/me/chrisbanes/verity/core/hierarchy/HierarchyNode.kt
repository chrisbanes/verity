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
