package me.chrisbanes.verity.core.hierarchy

/** Direct focus evidence. Paths are root-relative child indices, including empty containers. */
data class FocusedNode(val path: String, val resourceId: String?)

/** Complete-tree ID counts are internal evidence for pairwise identity, not focused output. */
@ConsistentCopyVisibility
data class FocusObservation internal constructor(
  val focusedNodes: List<FocusedNode>,
  internal val resourceIdCounts: Map<String, Int>,
)

fun observeFocus(root: HierarchyNode, checkpoint: () -> Unit = {}): FocusObservation {
  val focused = mutableListOf<FocusedNode>()
  val counts = mutableMapOf<String, Int>()
  fun visit(node: HierarchyNode, path: String) {
    checkpoint()
    val id = node.attributes["resource-id"]?.trim()?.takeIf { it.isNotEmpty() }
    if (id != null) counts[id] = counts.getOrDefault(id, 0) + 1
    if (FocusDetector.isFocused(node)) focused += FocusedNode(path, id)
    node.children.forEachIndexed { index, child -> visit(child, if (path == "/") "/$index" else "$path/$index") }
  }
  visit(root, "/")
  checkpoint()
  return FocusObservation(focused.toList(), counts.toMap())
}

private sealed interface FocusIdentity {
  data class Resource(val id: String) : FocusIdentity
  data class PositionedResource(val id: String, val path: String) : FocusIdentity
  data class Path(val path: String) : FocusIdentity
}

/**
 * Use resource-only identity when unique in both complete trees, otherwise use resource and
 * path (or path alone). Positional fallback cannot identify semantic nodes across sibling edits.
 */
fun hasFocusChanged(before: FocusObservation, after: FocusObservation, checkpoint: () -> Unit = {}): Boolean {
  fun identities(observation: FocusObservation): Set<FocusIdentity> = observation.focusedNodes.mapTo(mutableSetOf()) { node ->
    checkpoint()
    val id = node.resourceId
    when {
      id == null -> FocusIdentity.Path(node.path)
      before.resourceIdCounts[id] == 1 && after.resourceIdCounts[id] == 1 -> FocusIdentity.Resource(id)
      else -> FocusIdentity.PositionedResource(id, node.path)
    }
  }
  val changed = identities(before) != identities(after)
  checkpoint()
  return changed
}
