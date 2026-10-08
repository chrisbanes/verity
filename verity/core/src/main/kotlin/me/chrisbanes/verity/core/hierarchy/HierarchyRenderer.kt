package me.chrisbanes.verity.core.hierarchy

object HierarchyRenderer {

  fun render(root: HierarchyNode, filter: HierarchyFilter, checkpoint: () -> Unit = {}): String {
    val sb = StringBuilder()
    renderNode(root, filter, depth = 0, sb, checkpoint)
    return sb.toString()
  }

  private fun renderNode(
    node: HierarchyNode,
    filter: HierarchyFilter,
    depth: Int,
    sb: StringBuilder,
    checkpoint: () -> Unit,
  ) {
    checkpoint()
    val filteredAttrs = node.attributes
      .filter { (_, v) ->
        checkpoint()
        v.isNotEmpty() && v != "false"
      }
      .filter { (k, v) ->
        checkpoint()
        !(k == "enabled" && v == "true")
      }
      .filter { (k, _) ->
        checkpoint()
        filter.allowedKeys == null || k in filter.allowedKeys
      }

    val hasContent = filteredAttrs.isNotEmpty() || node.states.isNotEmpty()

    // Collapse empty containers with 0 or 1 children
    if (!hasContent && node.children.size <= 1) {
      for (child in node.children) {
        renderNode(child, filter, depth, sb, checkpoint)
      }
      return
    }

    if (hasContent) {
      val indent = "  ".repeat(depth)
      val attrStr = filteredAttrs.entries.joinToString(", ") {
        checkpoint()
        "${it.key}=${it.value}"
      }
      val stateStr = if (node.states.isNotEmpty()) {
        " (${node.states.onEach { checkpoint() }.sortedWith { first, second ->
          checkpoint()
          first.compareTo(second)
        }.joinToString(",") {
          checkpoint()
          it
        }})"
      } else {
        ""
      }

      sb.appendLine("$indent[$attrStr]$stateStr")
    }

    for (child in node.children) {
      renderNode(child, filter, if (hasContent) depth + 1 else depth, sb, checkpoint)
    }
  }
}
