package me.chrisbanes.verity.device.ios

import hierarchy.AXElement
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

/**
 * Converts Maestro's iOS [AXElement] into our platform-agnostic [HierarchyNode].
 *
 * Maps iOS accessibility attributes (label, value, identifier, title) and
 * states (hasFocus, selected, enabled) into the unified format.
 */
object XcTestTreeConverter {

  fun convert(element: AXElement): HierarchyNode {
    val attributes = buildMap<String, String> {
      if (element.label.isNotEmpty()) put("text", element.label)
      element.value?.takeIf { it.isNotEmpty() }?.let { put("value", it) }
      if (element.identifier.isNotEmpty()) put("resource-id", element.identifier)
      element.title?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
    }

    val states = buildSet {
      if (element.hasFocus) add("focused")
      if (element.selected) add("selected")
      if (!element.enabled) add("disabled")
    }

    return HierarchyNode(
      attributes = attributes,
      states = states,
      children = element.children.map { convert(it) },
    )
  }
}
