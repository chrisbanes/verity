package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

class McpFocusedTreeRendererTest {
  @Test
  fun `no state focus reports no focus and preserves snapshot identity`() {
    assertThat(McpFocusedTreeRenderer.render(HierarchyNode(attributes = mapOf("focused" to "true")), "snapshot", HierarchyFilter.CONTENT)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: no_focus
      focused: total=0 included=0 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=0 omitted_context=0 outside_context=1 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `leaf context cuts above two ancestors and stops after two descendants`() {
    val tree = node("R", node("A", node("B", node("F", node("D1", node("D2", node("D3"))), focused = true))))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=5 omitted_context=0 outside_context=2 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=1 source_depth=1 cut_above=1
      #1 [text=A]
        #2 [text=B]
          #3 [text=F] (focused)
            #4 [text=D1]
              #5 [text=D2]
                [omitted nodes=1 reason=outside_context]
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `sibling context includes only nearest two siblings and their immediate children`() {
    val siblings = (-5..5).map { i -> node("S$i", node("C$i", node("G$i")), focused = i == 0) }
    assertThat(McpFocusedTreeRenderer.render(node("R", *siblings.toTypedArray()), "snapshot", HierarchyFilter.CONTENT)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=12 omitted_context=0 outside_context=22 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [text=R]
        [omitted nodes=9 reason=outside_context]
        #10 [text=S-2]
          #11 [text=C-2]
            [omitted nodes=1 reason=outside_context]
        #13 [text=S-1]
          #14 [text=C-1]
            [omitted nodes=1 reason=outside_context]
        #16 [text=S0] (focused)
          #17 [text=C0]
            #18 [text=G0]
        #19 [text=S1]
          #20 [text=C1]
            [omitted nodes=1 reason=outside_context]
        #22 [text=S2]
          #23 [text=C2]
            [omitted nodes=1 reason=outside_context]
        [omitted nodes=9 reason=outside_context]
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `nested adjacent anchors and repeated positions merge once without collapsing identity`() {
    val repeated = node("equal", focused = true)
    val tree = node("R", node("container", repeated, repeated, node("label", node("detail")), focused = true))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=3 included=3 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=6 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [text=R]
        #1 [text=container] (focused)
          #2 [text=equal] (focused)
          #3 [text=equal] (focused)
          #4 [text=label]
            #5 [text=detail]
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `distinct focus windows form separate forests in source order`() {
    val tree = node("R", node("L", node("A", node("B", node("F", focused = true)))), node("Q", node("C", node("D", node("G", focused = true)))))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=2 included=2 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=6 omitted_context=0 outside_context=3 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=2 source_depth=2 cut_above=2
      #2 [text=A]
        #3 [text=B]
          #4 [text=F] (focused)
      region: node=6 source_depth=2 cut_above=2
      #6 [text=C]
        #7 [text=D]
          #8 [text=G] (focused)
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `real filters preserve focused states and qualify labels filtered from all attributes`() {
    val tree = HierarchyNode(attributes = mapOf("accessibilityText" to "label", "class" to "Widget"), states = setOf("focused"))
    val expected = listOf(
      HierarchyFilter.FOCUS to """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=1 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 1
      truncated: node_limit=false character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [] (focused)
      """.trimIndent() + "\n",
      HierarchyFilter.CONTENT to """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=1 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [accessibilityText=label] (focused)
      """.trimIndent() + "\n",
      HierarchyFilter.ALL to """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=1 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [accessibilityText=label, class=Widget] (focused)
      """.trimIndent() + "\n",
    )
    for ((filter, response) in expected) {
      assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", filter)).isEqualTo(response)
    }
    assertThat(McpFocusedTreeRenderer.render(HierarchyNode(states = setOf("focused")), "snapshot", HierarchyFilter.FOCUS)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=1 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [] (focused)
      """.trimIndent() + "\n",
    )
  }

  private fun node(text: String, vararg children: HierarchyNode, focused: Boolean = false) = HierarchyNode(
    attributes = mapOf("text" to text),
    states = if (focused) setOf("focused") else emptySet(),
    children = children.toList(),
  )
}
