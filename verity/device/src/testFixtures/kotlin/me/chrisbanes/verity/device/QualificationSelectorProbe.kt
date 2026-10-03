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
) {
  val sameNode: Boolean get() = outcome == "ready"
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
    if (labels.size != 1) return QualificationSelectorEvidence("ambiguous-label", labels.size, 0, 0)
    val approved = labels.single()
    val id = approved.attributes["resource-id"]?.takeIf(String::isNotBlank)
      ?: return QualificationSelectorEvidence("missing-id", 1, 0, 0)
    val bounds = approved.attributes["bounds"]?.takeIf(::hasUsableBounds)
      ?: return QualificationSelectorEvidence("missing-bounds", 1, 0, 0)

    val textRegex = escapedRegex(approvedLabel)
    val idRegex = escapedRegex(id)
    val textFilter = Filters.textMatches(textRegex)
    val idFilter = Filters.idMatches(idRegex)
    val textMatches = textFilter(nodes).size
    val idMatches = idFilter(nodes).size
    val textSelection = Filters.compose(Filters.deepestMatchingElement(textFilter), Filters.clickableFirst())(nodes)
    val idSelection = Filters.compose(Filters.deepestMatchingElement(idFilter), Filters.clickableFirst())(nodes)
    val selectedText = textSelection.firstOrNull()
    val selectedId = idSelection.firstOrNull()
    if (selectedText !== approved || selectedId !== approved || selectedText !== selectedId) {
      return QualificationSelectorEvidence(
        outcome = "selection-mismatch",
        labelMatches = labels.size,
        textMatches = textMatches,
        idMatches = idMatches,
      )
    }
    val boundsParts = parseBounds(bounds)
      ?: return QualificationSelectorEvidence("missing-bounds", labels.size, textMatches, idMatches)
    val (left, top, right, bottom) = boundsParts
    val visiblePercentage = UiElement(approved, Bounds(left, top, right - left, bottom - top))
      .getVisiblePercentage(deviceInfo.widthGrid, deviceInfo.heightGrid)
    if (!visiblePercentage.isFinite() || visiblePercentage < 0.1) {
      return QualificationSelectorEvidence("outside-viewport", labels.size, textMatches, idMatches)
    }
    return QualificationSelectorEvidence(
      outcome = "ready",
      labelMatches = labels.size,
      textMatches = textMatches,
      idMatches = idMatches,
      resourceId = id,
      selectedPath = paths[approved],
      selectedBounds = bounds,
    )
  }

  private fun escapedRegex(value: String) = Regex(Regex.escape(value), regexOptions)

  private fun parseBounds(value: String): List<Int>? {
    val parts = Regex("^\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]$").matchEntire(value)?.groupValues
      ?: return null
    return parts.drop(1).map { it.toIntOrNull() ?: return null }
  }

  private fun hasUsableBounds(value: String): Boolean {
    val parts = parseBounds(value) ?: return false
    val (left, top, right, bottom) = parts
    return right > left && bottom > top
  }
}
