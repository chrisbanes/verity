package me.chrisbanes.verity.mcp

import me.chrisbanes.verity.core.hierarchy.FocusDetector
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer

internal object McpFocusedTreeRenderer {
  private data class Entry(val node: HierarchyNode, val parent: Int, val depth: Int, val children: MutableList<Int> = mutableListOf(), var end: Int = 0)

  fun render(root: HierarchyNode, snapshotId: String, filter: HierarchyFilter, maxNodes: Int = 100, maxCharacters: Int = 12_000): String {
    // IDs belong to structural positions, even when equal nodes or the same object recur.
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
    val ranks = IntArray(entries.size) { 4 }
    fun candidate(id: Int, rank: Int) {
      candidates.add(id)
      ranks[id] = minOf(ranks[id], rank)
    }
    for (anchor in anchors) {
      candidate(anchor, 0)
      var parent = entries[anchor].parent
      repeat(2) {
        if (parent >= 0) {
          candidate(parent, 1)
          parent = entries[parent].parent
        }
      }
      if (entries[anchor].parent >= 0) {
        val siblings = entries[entries[anchor].parent].children
        val index = siblings.indexOf(anchor)
        for (i in maxOf(0, index - 2)..minOf(siblings.lastIndex, index + 2)) {
          if (i == index) continue
          candidate(siblings[i], 3)
          entries[siblings[i]].children.forEach { candidate(it, 3) }
        }
      }
      entries[anchor].children.forEach { child ->
        candidate(child, 2)
        entries[child].children.forEach { candidate(it, 2) }
      }
    }

    val regions = candidates.sorted().filter { entries[it].parent !in candidates }
    val filtered = anchors.count { id ->
      val node = entries[id].node.copy(states = emptySet(), children = emptyList())
      HierarchyRenderer.render(node, HierarchyFilter.ALL).isNotEmpty() && HierarchyRenderer.render(node, filter).isEmpty()
    }
    // The focused marker stays outside payload that can be shortened.
    val fullRows = entries.map { entry ->
      HierarchyRenderer.render(entry.node.copy(states = entry.node.states - "focused", children = emptyList()), filter)
        .trimEnd('\n').replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t").ifEmpty { if (FocusDetector.isFocused(entry.node)) "[]" else "[context]" }
    }
    val rows = fullRows.map { if (it.length > "[] [text truncated]".length) "[] [text truncated]" else it }.toMutableList()
    val selected = mutableSetOf<Int>()
    val failures = mutableMapOf<Int, String>()
    fun serialize(): String {
      val missing = IntArray(entries.size)
      for (id in entries.indices) {
        if (id in candidates && id !in selected) missing[id] = 1 + if (entries[id].parent in candidates) missing[entries[id].parent] else 0
      }
      // Reserve diagnostics for unvisited targets as if the walk ended here. A later
      // admission recomputes these predictions; first failures remain authoritative.
      fun reason(id: Int): String = failures[id] ?: if (selected.size + missing[id] > maxNodes) "node_limit" else "character_limit"
      return buildString {
        appendLine("snapshot_id: $snapshotId")
        appendLine("focus_status: ${if (anchors.isEmpty()) "no_focus" else "present"}")
        appendLine("focused: total=${anchors.size} included=${anchors.count { it in selected }} omitted_node_limit=${anchors.count { it !in selected && reason(it) == "node_limit" }} omitted_character_limit=${anchors.count { it !in selected && reason(it) == "character_limit" }}")
        appendLine("nodes: included=${selected.size} omitted_context=${candidates.size - selected.size} outside_context=${entries.size - candidates.size} omitted_regions=${regions.count { it !in selected }}")
        appendLine("focused_labels_filtered: $filtered")
        appendLine("truncated: node_limit=${candidates.any { it !in selected && reason(it) == "node_limit" }} character_limit=${candidates.any { it !in selected && reason(it) == "character_limit" }} text=${selected.any { rows[it] != fullRows[it] }}")
        val events = ArrayDeque<Pair<Int, String?>>()
        for (region in regions.asReversed()) if (region in selected) events.addLast(region to null)
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
          val focused = if (FocusDetector.isFocused(entry.node)) " (focused)" else ""
          appendLine("${"  ".repeat(depths[id])}#$id ${rows[id]}$focused")
          val children = mutableListOf<Pair<Int, String?>>()
          var omitted = 0
          var omittedReason = "outside_context"
          fun flush() {
            if (omitted > 0) children.add(-1 to "${"  ".repeat(depths[id] + 1)}[omitted nodes=$omitted reason=$omittedReason]")
            omitted = 0
          }
          for (child in entry.children) {
            if (child !in selected) {
              var omittedId = child
              while (omittedId < entries[child].end) {
                if (omittedId in selected) {
                  // A retained detached region renders its own descendants separately.
                  omittedId = entries[omittedId].end
                  continue
                }
                val nextReason = if (omittedId in candidates) reason(omittedId) else "outside_context"
                if (nextReason != omittedReason) flush()
                omittedReason = nextReason
                omitted++
                omittedId++
              }
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
    // Each admission includes its missing candidate ancestry or rejects the whole bundle.
    for (target in candidates.sortedWith(compareBy<Int> { ranks[it] }.thenBy { it })) {
      if (target in selected) continue
      val bundle = mutableListOf<Int>()
      var id = target
      while (id in candidates && id !in selected) {
        bundle.add(id)
        id = entries[id].parent
      }
      if (selected.size + bundle.size > maxNodes) {
        failures[target] = "node_limit"
        continue
      }
      selected.addAll(bundle)
      if (serialize().length > maxCharacters) {
        selected.removeAll(bundle.toSet())
        failures[target] = "character_limit"
      }
    }
    // Allocate payload by priority, then serialize the forest in source order.
    for (id in selected.sortedWith(compareBy<Int> { ranks[it] }.thenBy { it })) {
      if (rows[id] == fullRows[id]) continue
      val minimum = rows[id]
      rows[id] = fullRows[id]
      if (serialize().length <= maxCharacters) continue
      rows[id] = minimum
      val available = maxCharacters - serialize().length + minimum.length
      val suffix = "] [text truncated]"
      val payload = fullRows[id].drop(1)
      var units = minOf(payload.length, maxOf(0, available - suffix.length - 1))
      if (units > 0 && units < payload.length && payload[units - 1].isHighSurrogate() && payload[units].isLowSurrogate()) units--
      rows[id] = "[${payload.take(units)}$suffix"
    }
    return serialize()
  }
}
