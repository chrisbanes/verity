package me.chrisbanes.verity.core.hierarchy

import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.Platform

/** A node's `bounds` attribute, `[left,top][right,bottom]`, with positive width and height. */
data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  companion object {
    private val PATTERN = Regex("""^\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]$""")

    fun parse(value: String): NodeBounds? {
      val match = PATTERN.matchEntire(value) ?: return null
      val (left, top, right, bottom) = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
      return if (right > left && bottom > top) NodeBounds(left, top, right, bottom) else null
    }
  }
}

/** This node's parsed `bounds` attribute, or null when absent or invalid. */
fun HierarchyNode.bounds(): NodeBounds? = attributes["bounds"]?.let(NodeBounds::parse)

/**
 * Predicts how many D-pad presses move focus to a target, from one captured tree. Predictions are
 * geometric and may differ from the app's real focus order; callers must verify after pressing.
 */
object FocusStepPlanner {
  private val DIRECTIONS = mapOf(
    "remote dpad up" to Direction.UP,
    "remote dpad down" to Direction.DOWN,
    "remote dpad left" to Direction.LEFT,
    "remote dpad right" to Direction.RIGHT,
  )

  // shortcut: a fixed pixel tolerance groups a row's leading edges into one band; scale with density if TV trees misgroup.
  private const val BAND_TOLERANCE = 8

  /** The direction of a loop body that is exactly one mapped D-pad press on Android TV, else null. */
  fun batchDirection(interactions: List<Interaction>?, platform: Platform): Direction? {
    if (platform != Platform.ANDROID_TV) return null
    val press = interactions?.singleOrNull() as? Interaction.KeyPress ?: return null
    return DIRECTIONS[press.keyName.lowercase()]
  }

  /** Bands from focus up to and including [target]'s, or null when [target] is not beyond focus. */
  fun stepsToTarget(tree: HierarchyNode, direction: Direction, target: NodeBounds): Int? {
    val (focus, bands) = plan(tree, direction) ?: return null
    val centre = target.along(direction).let { it.first + it.last }
    if (centre <= 2 * focus.last) return null
    return bands.count { 2 * it <= centre }.takeIf { it >= 1 }
  }

  /** Every band beyond focus in the focused node's container. */
  fun stepsPastVisible(tree: HierarchyNode, direction: Direction): Int? = plan(tree, direction)?.second?.size?.takeIf { it >= 1 }

  /** Focus's span along [direction], and the sorted leading edges of the bands beyond it. */
  private fun plan(tree: HierarchyNode, direction: Direction): Pair<IntRange, List<Int>>? {
    val path = focusedPath(tree) ?: return null
    val focus = path.last().bounds() ?: return null
    val span = focus.along(direction)
    val cross = focus.across(direction)
    for (ancestor in path.asReversed().drop(1)) {
      val beyond = candidates(ancestor).mapNotNull { it.bounds() }.filter { candidate ->
        val along = candidate.along(direction)
        val across = candidate.across(direction)
        along.first + along.last > 2 * span.last && across.first < cross.last && cross.first < across.last
      }
      if (beyond.isEmpty()) continue
      val bands = mutableListOf<Int>()
      beyond.map { it.along(direction).first }.sorted().forEach { edge ->
        if (bands.isEmpty() || edge - bands.last() > BAND_TOLERANCE) bands += edge
      }
      return span to bands
    }
    return null
  }

  /** Root-to-node path of the deepest focused node, or null when nothing is focused. */
  private fun focusedPath(root: HierarchyNode): List<HierarchyNode>? {
    var best: List<HierarchyNode>? = null
    fun visit(node: HierarchyNode, path: List<HierarchyNode>) {
      val here = path + node
      if (FocusDetector.isFocused(node) && here.size > (best?.size ?: 0)) best = here
      node.children.forEach { visit(it, here) }
    }
    visit(root, emptyList())
    return best
  }

  /** Focusable leaves: a candidate containing another candidate is dropped. */
  private fun candidates(node: HierarchyNode): List<HierarchyNode> {
    val nested = node.children.flatMap(::candidates)
    if (nested.isNotEmpty()) return nested
    val focusable = FocusDetector.isFocused(node) || "clickable" in node.states || node.attributes["focusable"] == "true"
    return if (focusable && node.bounds() != null) listOf(node) else emptyList()
  }

  /** The span along [direction], oriented so that moving forward increases both ends. */
  private fun NodeBounds.along(direction: Direction): IntRange = when (direction) {
    Direction.DOWN -> top..bottom
    Direction.UP -> -bottom..-top
    Direction.RIGHT -> left..right
    Direction.LEFT -> -right..-left
  }

  private fun NodeBounds.across(direction: Direction): IntRange = when (direction) {
    Direction.UP, Direction.DOWN -> left..right
    Direction.LEFT, Direction.RIGHT -> top..bottom
  }
}
