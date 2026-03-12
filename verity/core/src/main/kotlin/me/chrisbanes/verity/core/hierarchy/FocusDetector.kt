package me.chrisbanes.verity.core.hierarchy

/**
 * Lenient focus detection for accessibility trees.
 *
 * Android TV often places `focused=true` on a container while the actual
 * text lives in a sibling or child. This algorithm checks multiple
 * relationship types between focused nodes and text nodes.
 */
object FocusDetector {

  private data class FlatEntry(
    val depth: Int,
    val focused: Boolean,
    val hasText: Boolean,
  )

  /** Check focus/text relationship by walking a [HierarchyNode] tree directly. */
  fun containsFocused(root: HierarchyNode, text: String): Boolean {
    val textLower = text.lowercase()
    val flat = mutableListOf<FlatEntry>()

    fun flatten(node: HierarchyNode, depth: Int) {
      flat.add(
        FlatEntry(
          depth = depth,
          focused = "focused" in node.states,
          hasText = node.attributes.values.any { it.lowercase().contains(textLower) },
        ),
      )
      node.children.forEach { flatten(it, depth + 1) }
    }
    flatten(root, 0)

    return checkRelationships(flat)
  }

  /**
   * Check focus/text relationship by parsing rendered hierarchy text.
   *
   * Note: The tree depth calculation assumes an exact 2-space indentation
   * per level, tightly coupled to how [HierarchyRenderer] outputs the tree.
   */
  fun containsFocused(hierarchy: String, text: String): Boolean {
    if (hierarchy.isBlank()) return false

    val textLower = text.lowercase()
    val flat = hierarchy.lines()
      .filter { it.isNotBlank() }
      .map { line ->
        FlatEntry(
          depth = (line.length - line.trimStart().length) / 2,
          focused = line.contains("(focused") || line.contains(",focused"),
          hasText = line.lowercase().contains(textLower),
        )
      }

    return checkRelationships(flat)
  }

  private fun checkRelationships(entries: List<FlatEntry>): Boolean {
    val focusedIndices = entries.indices.filter { entries[it].focused }
    val textIndices = entries.indices.filter { entries[it].hasText }

    if (focusedIndices.isEmpty() || textIndices.isEmpty()) return false

    for (fi in focusedIndices) {
      for (ti in textIndices) {
        if (fi == ti) return true // Same node
        if (isDescendant(entries, parent = fi, child = ti)) return true
        if (isDescendant(entries, parent = ti, child = fi)) return true // Ancestor of focused
        if (isSibling(entries, fi, ti)) return true
      }
    }
    return false
  }

  private fun isDescendant(entries: List<FlatEntry>, parent: Int, child: Int): Boolean {
    if (child <= parent) return false
    if (entries[child].depth <= entries[parent].depth) return false
    // Check that no node between parent and child is at parent's depth or shallower
    for (i in (parent + 1) until child) {
      if (entries[i].depth <= entries[parent].depth) return false
    }
    return true
  }

  private fun isSibling(entries: List<FlatEntry>, a: Int, b: Int): Boolean {
    if (entries[a].depth != entries[b].depth) return false
    // Top-level nodes (depth 0) don't share a meaningful parent
    if (entries[a].depth == 0) return false
    val (first, second) = if (a < b) a to b else b to a
    // Siblings share the same parent — no node between them is at a shallower depth
    for (i in (first + 1) until second) {
      if (entries[i].depth < entries[first].depth) return false
    }
    return true
  }
}
