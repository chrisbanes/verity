package me.chrisbanes.verity.core.hierarchy

private val VOLATILE_ATTRIBUTES = setOf("text", "accessibilityText")

// One or two `[-]h:mm[:ss]` tokens, optionally joined by `/`, e.g. a player's `00:12 / 01:30`.
private val CLOCK = Regex("""^\s*-?\d{1,2}:\d{2}(:\d{2})?(\s*/\s*-?\d{1,2}:\d{2}(:\d{2})?)?\s*$""")

/**
 * Whether [a] and [b] have the same structure: states, attribute keys and values, and children in
 * order. A `text` or `accessibilityText` value is ignored only when both sides are clock-like, so a
 * ticking playback position does not count as a change but a title change does.
 */
fun isStructurallyEqual(a: HierarchyNode, b: HierarchyNode, checkpoint: () -> Unit = {}): Boolean {
  checkpoint()
  if (a.states != b.states || a.attributes.keys != b.attributes.keys || a.children.size != b.children.size) return false
  for ((key, value) in a.attributes) {
    val other = b.attributes.getValue(key)
    if (value != other && !(key in VOLATILE_ATTRIBUTES && CLOCK.matches(value) && CLOCK.matches(other))) return false
  }
  return a.children.indices.all { isStructurallyEqual(a.children[it], b.children[it], checkpoint) }
}
