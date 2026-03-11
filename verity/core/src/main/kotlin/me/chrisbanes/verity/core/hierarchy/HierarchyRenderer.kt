package me.chrisbanes.verity.core.hierarchy

object HierarchyRenderer {

  fun render(root: HierarchyNode, filter: HierarchyFilter): String {
    val sb = StringBuilder()
    renderNode(root, filter, depth = 0, sb)
    return sb.toString()
  }

  private fun renderNode(
    node: HierarchyNode,
    filter: HierarchyFilter,
    depth: Int,
    sb: StringBuilder,
  ) {
    val filteredAttrs = node.attributes
      .filter { (_, v) -> v.isNotEmpty() && v != "false" }
      .filter { (k, v) -> !(k == "enabled" && v == "true") }
      .filter { (k, _) -> filter.allowedKeys == null || k in filter.allowedKeys }

    val hasContent = filteredAttrs.isNotEmpty() || node.states.isNotEmpty()

    // Collapse empty containers with 0 or 1 children
    if (!hasContent && node.children.size <= 1) {
      for (child in node.children) {
        renderNode(child, filter, depth, sb)
      }
      return
    }

    if (hasContent) {
      val indent = "  ".repeat(depth)
      val attrStr = filteredAttrs.entries.joinToString(", ") { "${it.key}=${it.value}" }
      val stateStr = if (node.states.isNotEmpty()) {
        " (${node.states.sorted().joinToString(",")})"
      } else {
        ""
      }

      sb.appendLine("$indent[$attrStr]$stateStr")
    }

    for (child in node.children) {
      renderNode(child, filter, if (hasContent) depth + 1 else depth, sb)
    }
  }
}
