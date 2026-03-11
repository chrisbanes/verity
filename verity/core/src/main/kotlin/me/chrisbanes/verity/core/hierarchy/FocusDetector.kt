package me.chrisbanes.verity.core.hierarchy

/**
 * Lenient focus detection for accessibility trees.
 *
 * Android TV often places `focused=true` on a container while the actual
 * text lives in a sibling or child. This algorithm checks multiple
 * relationship types between focused nodes and text nodes.
 *
 * Note: The tree depth calculation currently assumes an exact 2-space
 * indentation per level. This is tightly coupled to how [HierarchyRenderer]
 * outputs the tree. External raw outputs with different indentation
 * may fail or miscalculate depth.
 */
object FocusDetector {

    private data class ParsedNode(
        val depth: Int,
        val focused: Boolean,
        val text: String,  // the raw line content
        val textLower: String, // cached lowercase content
    )

    fun containsFocused(hierarchy: String, text: String): Boolean {
        if (hierarchy.isBlank()) return false

        val nodes = hierarchy.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val depth = line.length - line.trimStart().length
                val focused = line.contains("(focused") || line.contains(",focused")
                ParsedNode(
                    depth = depth / 2,
                    focused = focused,
                    text = line,
                    textLower = line.lowercase(),
                )
            }

        val textLower = text.lowercase()
        val focusedIndices = nodes.indices.filter { nodes[it].focused }
        val textIndices = nodes.indices.filter { nodes[it].textLower.contains(textLower) }

        if (focusedIndices.isEmpty() || textIndices.isEmpty()) return false

        for (fi in focusedIndices) {
            for (ti in textIndices) {
                if (fi == ti) return true  // Same node
                if (isDescendant(nodes, parent = fi, child = ti)) return true
                if (isDescendant(nodes, parent = ti, child = fi)) return true  // Ancestor of focused
                if (isSibling(nodes, fi, ti)) return true
            }
        }
        return false
    }

    private fun isDescendant(nodes: List<ParsedNode>, parent: Int, child: Int): Boolean {
        if (child <= parent) return false
        if (nodes[child].depth <= nodes[parent].depth) return false
        // Check that no node between parent and child is at parent's depth or shallower
        for (i in (parent + 1) until child) {
            if (nodes[i].depth <= nodes[parent].depth) return false
        }
        return true
    }

    private fun isSibling(nodes: List<ParsedNode>, a: Int, b: Int): Boolean {
        if (nodes[a].depth != nodes[b].depth) return false
        // Top-level nodes (depth 0) don't share a meaningful parent
        if (nodes[a].depth == 0) return false
        val (first, second) = if (a < b) a to b else b to a
        // Siblings share the same parent — no node between them is at a shallower depth
        for (i in (first + 1) until second) {
            if (nodes[i].depth < nodes[first].depth) return false
        }
        return true
    }
}
