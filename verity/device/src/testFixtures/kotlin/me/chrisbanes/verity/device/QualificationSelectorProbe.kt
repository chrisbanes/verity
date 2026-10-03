package me.chrisbanes.verity.device

import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import maestro.Bounds
import maestro.DeviceInfo
import maestro.Filters
import maestro.TreeNode
import maestro.UiElement
import me.chrisbanes.verity.device.android.AndroidDeviceSession
import me.chrisbanes.verity.device.ios.IosDeviceSession

/** Safe summary of how Maestro 2.11 selects the approved Settings fixture node. */
data class QualificationSelectorEvidence(
  val outcome: String,
  val labelMatches: Int,
  val textMatches: Int,
  val idMatches: Int,
  val resourceId: String? = null,
  val selectedPath: String? = null,
  val selectedBounds: String? = null,
  val elapsedMillis: Long = 0,
  val selectionProof: String? = null,
  val textSelectedPath: String? = null,
  val textSelectedBounds: String? = null,
  val idSelectedPath: String? = null,
  val idSelectedBounds: String? = null,
) {
  val sameNode: Boolean get() = outcome == "ready" && selectionProof == "same-node"
  val sameFixture: Boolean get() = outcome == "ready"
}

/** Qualification-only access to the session-owned SDK snapshot and public selector filters. */
object QualificationSelectorProbe {
  private val regexOptions = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
  private val labelAttributes = listOf("text", "accessibilityText", "hintText", "error")

  @Suppress("DEPRECATION")
  suspend fun capture(session: DeviceSession, approvedLabel: String): QualificationSelectorEvidence {
    val started = System.nanoTime()
    val (root, deviceInfo) = withContext(Dispatchers.IO) {
      when (session) {
        is AndroidDeviceSession -> session.maestro.viewHierarchy(false).root to session.maestro.deviceInfo()
        is IosDeviceSession -> session.maestro.viewHierarchy(false).root to session.maestro.deviceInfo()
        else -> error("Unsupported qualification session")
      }
    }
    return inspect(root, approvedLabel, deviceInfo).copy(elapsedMillis = (System.nanoTime() - started) / 1_000_000)
  }

  /** Uses SDK filters over one immutable snapshot; no node text is returned to the caller. */
  fun inspect(
    root: TreeNode,
    approvedLabel: String,
    deviceInfo: DeviceInfo = DeviceInfo(maestro.device.Platform.ANDROID, 100, 100, 100, 100),
  ): QualificationSelectorEvidence {
    val paths = IdentityHashMap<TreeNode, String>()
    fun collect(node: TreeNode, path: String) {
      paths[node] = path
      node.children.forEachIndexed { index, child -> collect(child, "$path.$index") }
    }
    collect(root, "0")
    val nodes = root.aggregate()
    val labels = nodes.filter { node -> labelAttributes.any { node.attributes[it] == approvedLabel } }
    if (labels.isEmpty()) return QualificationSelectorEvidence("not-ready", 0, 0, 0)
    if (!labels.formSingleAncestryChain(paths)) return QualificationSelectorEvidence("ambiguous-label", labels.size, 0, 0)
    val ids = labels.mapNotNull { it.attributes["resource-id"]?.takeIf(String::isNotBlank) }.distinct()
    if (ids.isEmpty()) return QualificationSelectorEvidence("missing-id", labels.size, 0, 0)
    if (ids.size != 1) return QualificationSelectorEvidence("selection-mismatch", labels.size, 0, 0)
    val id = ids.single()
    val textRegex = escapedRegex(approvedLabel)
    val idRegex = escapedRegex(id)
    val textFilter = Filters.textMatches(textRegex)
    val idFilter = Filters.idMatches(idRegex)
    val textMatchingNodes = textFilter(nodes)
    val idMatchingNodes = idFilter(nodes)
    val textMatches = textMatchingNodes.size
    val idMatches = idMatchingNodes.size
    val textSelection = Filters.compose(Filters.deepestMatchingElement(textFilter), Filters.clickableFirst())(nodes)
    val idSelection = Filters.compose(Filters.deepestMatchingElement(idFilter), Filters.clickableFirst())(nodes)
    val selectedText = textSelection.firstOrNull()
    val selectedId = idSelection.firstOrNull()
    val labelSet = java.util.Collections.newSetFromMap(IdentityHashMap<TreeNode, Boolean>()).apply { addAll(labels) }
    if (selectedText == null || selectedId == null || selectedText !in labelSet || selectedId !in labelSet) {
      return unavailable("selection-mismatch", labels.size, textMatches, idMatches)
    }
    val textPath = paths[selectedText] ?: return unavailable("selection-mismatch", labels.size, textMatches, idMatches)
    val idPath = paths[selectedId] ?: return unavailable("selection-mismatch", labels.size, textMatches, idMatches)
    val selectedIdValue = selectedId.attributes["resource-id"]?.takeIf(String::isNotBlank)
    if (selectedIdValue != id) return unavailable("selection-mismatch", labels.size, textMatches, idMatches)
    val selectedTextBounds = selectedText.attributes["bounds"]?.takeIf(::hasUsableBounds)
      ?: return unavailable("missing-bounds", labels.size, textMatches, idMatches)
    val selectedIdBounds = selectedId.attributes["bounds"]?.takeIf(::hasUsableBounds)
      ?: return unavailable("missing-bounds", labels.size, textMatches, idMatches)
    val textRect = parseBounds(selectedTextBounds) ?: return unavailable("missing-bounds", labels.size, textMatches, idMatches)
    val idRect = parseBounds(selectedIdBounds) ?: return unavailable("missing-bounds", labels.size, textMatches, idMatches)
    val sameNode = selectedText === selectedId
    if (!sameNode && (!textPath.isDescendantOf(idPath) || !idRect.contains(textRect))) {
      return unavailable("selection-mismatch", labels.size, textMatches, idMatches)
    }
    if (!selectedText.isVisible(textRect, deviceInfo) || (!sameNode && !selectedId.isVisible(idRect, deviceInfo))) {
      return unavailable("outside-viewport", labels.size, textMatches, idMatches)
    }
    val proof = if (sameNode) "same-node" else "same-row-descendant"
    return QualificationSelectorEvidence(
      outcome = "ready",
      labelMatches = labels.size,
      textMatches = textMatches,
      idMatches = idMatches,
      resourceId = id,
      selectedPath = idPath,
      selectedBounds = selectedIdBounds,
      selectionProof = proof,
      textSelectedPath = textPath,
      textSelectedBounds = selectedTextBounds,
      idSelectedPath = idPath,
      idSelectedBounds = selectedIdBounds,
    )
  }

  private fun unavailable(outcome: String, labels: Int, text: Int, id: Int) = QualificationSelectorEvidence(outcome, labels, text, id)

  private fun List<TreeNode>.formSingleAncestryChain(paths: IdentityHashMap<TreeNode, String>): Boolean = all { first -> all { second -> first === second || paths.getValue(first).isDescendantOf(paths.getValue(second)) || paths.getValue(second).isDescendantOf(paths.getValue(first)) } }

  private fun String.isDescendantOf(parent: String) = startsWith("$parent.")

  private fun TreeNode.isVisible(rect: List<Int>, deviceInfo: DeviceInfo): Boolean {
    val (left, top, right, bottom) = rect
    val percentage = UiElement(this, Bounds(left, top, right - left, bottom - top))
      .getVisiblePercentage(deviceInfo.widthGrid, deviceInfo.heightGrid)
    return percentage.isFinite() && percentage >= 0.1
  }

  private fun escapedRegex(value: String) = Regex(Regex.escape(value), regexOptions)

  private fun parseBounds(value: String): List<Int>? {
    val parts = Regex("^\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]$").matchEntire(value)?.groupValues
      ?: return null
    return parts.drop(1).map { it.toIntOrNull() ?: return null }
  }

  private fun List<Int>.contains(other: List<Int>): Boolean = this[0] <= other[0] && this[1] <= other[1] && this[2] >= other[2] && this[3] >= other[3]

  private fun hasUsableBounds(value: String): Boolean {
    val parts = parseBounds(value) ?: return false
    val (left, top, right, bottom) = parts
    return right > left && bottom > top
  }
}
