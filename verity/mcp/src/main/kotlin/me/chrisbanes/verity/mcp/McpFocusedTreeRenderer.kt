package me.chrisbanes.verity.mcp

import me.chrisbanes.verity.core.hierarchy.FocusDetector
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer

internal object McpFocusedTreeRenderer {
  private data class Entry(val node: HierarchyNode, val parent: Int, val depth: Int, val children: MutableList<Int> = mutableListOf(), var end: Int = 0)

  fun render(root: HierarchyNode, snapshotId: String, filter: HierarchyFilter): String {
    val entries = mutableListOf<Entry>()
    val stack = ArrayDeque<Triple<HierarchyNode, Int, Int>>()
    stack.addLast(Triple(root, -1, 0))
    while (stack.isNotEmpty()) {
      val (node, parent, depth) = stack.removeLast()
      val id = entries.size
      entries.add(Entry(node, parent, depth))
      if (parent >= 0) entries[parent].children.add(id)
      node.children.asReversed().forEach { stack.addLast(Triple(it, id, depth + 1)) }
    }
    entries.indices.reversed().forEach { id ->
      entries[id].end = entries[id].children.lastOrNull()?.let { entries[it].end } ?: (id + 1)
    }
    val anchors = entries.indices.filter { FocusDetector.isFocused(entries[it].node) }
    val candidates = mutableSetOf<Int>()
    for (anchor in anchors) {
      candidates.add(anchor)
      var parent = entries[anchor].parent
      repeat(2) {
        if (parent >= 0) {
          candidates.add(parent)
          parent = entries[parent].parent
        }
      }
      if (entries[anchor].parent >= 0) {
        val siblings = entries[entries[anchor].parent].children
        val index = siblings.indexOf(anchor)
        for (i in maxOf(0, index - 2)..minOf(siblings.lastIndex, index + 2)) {
          if (i == index) continue
          candidates.add(siblings[i])
          candidates.addAll(entries[siblings[i]].children)
        }
      }
      entries[anchor].children.forEach { child ->
        candidates.add(child)
        candidates.addAll(entries[child].children)
      }
    }
    val regions = candidates.sorted().filter { entries[it].parent !in candidates }
    val filtered = anchors.count { id ->
      val node = entries[id].node.copy(states = emptySet(), children = emptyList())
      HierarchyRenderer.render(node, HierarchyFilter.ALL).isNotEmpty() && HierarchyRenderer.render(node, filter).isEmpty()
    }
    return buildString {
      appendLine("snapshot_id: $snapshotId")
      appendLine("focus_status: ${if (anchors.isEmpty()) "no_focus" else "present"}")
      appendLine("focused: total=${anchors.size} included=${anchors.size} omitted_node_limit=0 omitted_character_limit=0")
      appendLine("nodes: included=${candidates.size} omitted_context=0 outside_context=${entries.size - candidates.size} omitted_regions=0")
      appendLine("focused_labels_filtered: $filtered")
      appendLine("truncated: node_limit=false character_limit=false text=false")
      val events = ArrayDeque<Pair<Int, String?>>()
      for (region in regions.asReversed()) events.addLast(region to null)
      val depths = IntArray(entries.size)
      while (events.isNotEmpty()) {
        val (id, marker) = events.removeLast()
        if (marker != null) {
          appendLine(marker)
          continue
        }
        val entry = entries[id]
        if (id in regions) {
          appendLine("region: node=$id source_depth=${entry.depth} cut_above=${entry.depth}")
        } else {
          depths[id] = depths[entry.parent] + 1
        }
        val row = HierarchyRenderer.render(entry.node.copy(children = emptyList()), filter).trimEnd('\n').ifEmpty { "[context]" }
        appendLine("${"  ".repeat(depths[id])}#$id $row")
        val children = mutableListOf<Pair<Int, String?>>()
        var omitted = 0
        fun flush() {
          if (omitted > 0) children.add(-1 to "${"  ".repeat(depths[id] + 1)}[omitted nodes=$omitted reason=outside_context]")
          omitted = 0
        }
        for (child in entry.children) {
          if (child !in candidates) {
            omitted += entries[child].end - child
          } else {
            flush()
            children.add(child to null)
          }
        }
        flush()
        children.asReversed().forEach(events::addLast)
      }
    }
  }
}
