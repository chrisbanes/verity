package me.chrisbanes.verity.device.android

import maestro.TreeNode
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

/**
 * Converts Maestro's [TreeNode] into our platform-agnostic [HierarchyNode].
 */
object MaestroTreeConverter {

    fun convert(node: TreeNode): HierarchyNode {
        val attributes = node.attributes
            .filterValues { it.isNotEmpty() }

        val states = buildSet {
            if (node.focused == true) add("focused")
            if (node.selected == true) add("selected")
            if (node.checked == true) add("checked")
            if (node.enabled == false) add("disabled")
            if (node.clickable == true) add("clickable")
        }

        return HierarchyNode(
            attributes = attributes,
            states = states,
            children = node.children.map { convert(it) },
        )
    }
}
