package me.chrisbanes.verity.mcp

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.chrisbanes.verity.core.hierarchy.FocusDetector
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

/** Structural comparison of captured full trees, using child-index paths as identity. */
object HierarchyDiff {
  private const val MAX_SAMPLES = 20
  private const val MAX_SAMPLE_CHARACTERS = 500
  private const val MAX_RENDERED_CHARACTERS = 16_000

  private class Category(entries: List<String>) {
    val count = entries.size
    val samples = entries.take(MAX_SAMPLES).map(::boundedSample).toMutableList()

    fun toJson(): JsonObject = buildJsonObject {
      put("count", count)
      put("samples", JsonArray(samples))
      put("omitted_count", count - samples.size)
      put("truncated", count > samples.size || samples.any { it.getValue("truncated").jsonPrimitive.boolean })
    }
  }

  fun render(sessionId: UUID, pair: ResolvedHierarchySnapshotPair): String {
    val before = flatten(pair.before)
    val after = flatten(pair.after)
    val added = after.filterKeys { it !in before }.map { (path, node) -> describe(path, node) }
    val removed = before.filterKeys { it !in after }.map { (path, node) -> describe(path, node) }
    val changed = before.mapNotNull { (path, node) ->
      val other = after[path]
      if (other != null && (node.attributes != other.attributes || node.states != other.states)) {
        "${describe(path, node)} -> ${describe(path, other)}"
      } else {
        null
      }
    }
    val addedCategory = Category(added)
    val removedCategory = Category(removed)
    val changedCategory = Category(changed)
    val focusBefore = Category(before.filterValues(FocusDetector::isFocused).map { (path, node) -> describe(path, node) })
    val focusAfter = Category(after.filterValues(FocusDetector::isFocused).map { (path, node) -> describe(path, node) })
    val priority = listOf(focusBefore, focusAfter, changedCategory, addedCategory, removedCategory)
    var renderedTruncated = false
    fun document(): String = buildJsonObject {
      put("session_id", sessionId.toString())
      put("before_snapshot_id", pair.beforeSnapshotId.toString())
      put("after_snapshot_id", pair.afterSnapshotId.toString())
      put("added", addedCategory.toJson())
      put("removed", removedCategory.toJson())
      put("changed", changedCategory.toJson())
      put("focus_before", focusBefore.toJson())
      put("focus_after", focusAfter.toJson())
      put("rendered_truncated", renderedTruncated)
    }.toString()

    var rendered = document()
    // UUIDs, integer counts, flags and the five empty containers always fit the budget.
    // Remove complete samples in reverse priority, retaining full counts and both focus summaries.
    while (rendered.length > MAX_RENDERED_CHARACTERS) {
      priority.last { it.samples.isNotEmpty() }.samples.removeLast()
      renderedTruncated = true
      rendered = document()
    }
    return rendered
  }

  private fun flatten(root: HierarchyNode): Map<String, HierarchyNode> {
    val entries = linkedMapOf<String, HierarchyNode>()
    fun visit(path: String, node: HierarchyNode) {
      entries[path] = node
      node.children.forEachIndexed { index, child -> visit(if (path == "/") "/$index" else "$path/$index", child) }
    }
    visit("/", root)
    return entries
  }

  private fun describe(path: String, node: HierarchyNode): String {
    val attributes = JsonObject(node.attributes.toSortedMap().mapValues { JsonPrimitive(it.value) })
    val states = JsonArray(node.states.sorted().map { JsonPrimitive(it) })
    return "$path attributes=$attributes states=$states"
  }

  private fun sample(text: String, truncated: Boolean): JsonObject = buildJsonObject {
    put("text", text)
    put("truncated", truncated)
  }

  private fun boundedSample(text: String): JsonObject {
    val full = sample(text, false)
    if (full.toString().length <= MAX_SAMPLE_CHARACTERS) return full
    var low = 0
    var high = text.codePointCount(0, text.length)
    while (low < high) {
      val middle = (low + high + 1) / 2
      val end = text.offsetByCodePoints(0, middle)
      if (sample(text.substring(0, end) + "…", true).toString().length <= MAX_SAMPLE_CHARACTERS) {
        low = middle
      } else {
        high = middle - 1
      }
    }
    return sample(text.substring(0, text.offsetByCodePoints(0, low)) + "…", true)
  }
}
