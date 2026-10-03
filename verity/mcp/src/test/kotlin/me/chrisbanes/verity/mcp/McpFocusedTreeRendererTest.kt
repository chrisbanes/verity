package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

class McpFocusedTreeRendererTest {
  @Test
  fun `omitted outside branch separates nested node capped focus region in source order`() {
    val tree = node("R", node("P", node("F", focused = true)), node("X", node("Y", node("Z", node("W", node("A", node("B", node("G", focused = true))))))), node("tail"))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT, maxNodes = 3)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=2 included=1 omitted_node_limit=1 omitted_character_limit=0
      nodes: included=3 omitted_context=3 outside_context=5 omitted_regions=1
      focused_labels_filtered: 0
      truncated: node_limit=true character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [text=R]
        #1 [text=P]
          #2 [text=F] (focused)
        [omitted nodes=4 reason=outside_context]
        [omitted nodes=3 reason=node_limit]
        [omitted nodes=1 reason=outside_context]
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `omitted outside branch separates nested character capped focus region in source order`() {
    val tree = node("R", node("P", node("F", focused = true)), node("X", node("Y", node("Z", node("W", node("A", node("B", node("G", focused = true))))))), node("tail"))
    val expected = """
      snapshot_id: snapshot
      focus_status: present
      focused: total=2 included=1 omitted_node_limit=0 omitted_character_limit=1
      nodes: included=3 omitted_context=3 outside_context=5 omitted_regions=1
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=true text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [text=R]
        #1 [text=P]
          #2 [text=F] (focused)
        [omitted nodes=4 reason=outside_context]
        [omitted nodes=3 reason=character_limit]
        [omitted nodes=1 reason=outside_context]
    """.trimIndent() + "\n"
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT, maxCharacters = expected.length)).isEqualTo(expected)
  }

  @Test
  fun `retained detached region owns its omitted descendants exactly once`() {
    val tree = node("R", node("P", node("F", focused = true)), node("X", node("Y", node("Z", node("W", node("A", node("B", node("G", focused = true))))))), node("tail"))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT, maxNodes = 4)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=2 included=1 omitted_node_limit=1 omitted_character_limit=0
      nodes: included=4 omitted_context=2 outside_context=5 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=true character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [text=R]
        #1 [text=P]
          #2 [text=F] (focused)
        [omitted nodes=5 reason=outside_context]
      region: node=7 source_depth=5 cut_above=5
      #7 [text=A]
        [omitted nodes=2 reason=node_limit]
      """.trimIndent() + "\n",
    )
  }

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
    val tree = node("R", node("container", repeated, repeated, node("label", node("detail")), focused = true), node("other", repeated))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=4 included=4 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=8 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [text=R]
        #1 [text=container] (focused)
          #2 [text=equal] (focused)
          #3 [text=equal] (focused)
          #4 [text=label]
            #5 [text=detail]
        #6 [text=other]
          #7 [text=equal] (focused)
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

  @Test
  fun `eight node budget reserves focus ancestry then descendants before sibling context`() {
    val tree = node("R", node("A", node("P", node("S-2", node("C-2")), node("S-1", node("C-1")), node("F", node("D1", node("G1")), node("D2", node("G2")), focused = true), node("S+1", node("C+1")), node("S+2", node("C+2")))))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT, maxNodes = 8)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=8 omitted_context=7 outside_context=1 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=true character_limit=false text=false
      region: node=1 source_depth=1 cut_above=1
      #1 [text=A]
        #2 [text=P]
          #3 [text=S-2]
            [omitted nodes=1 reason=node_limit]
          [omitted nodes=2 reason=node_limit]
          #7 [text=F] (focused)
            #8 [text=D1]
              #9 [text=G1]
            #10 [text=D2]
              #11 [text=G2]
          [omitted nodes=4 reason=node_limit]
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `ninth node goes to preceding sibling child before next sibling`() {
    val tree = node("R", node("A", node("P", node("S-2", node("C-2")), node("S-1", node("C-1")), node("F", node("D1", node("G1")), node("D2", node("G2")), focused = true), node("S+1", node("C+1")), node("S+2", node("C+2")))))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT, maxNodes = 9)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=9 omitted_context=6 outside_context=1 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=true character_limit=false text=false
      region: node=1 source_depth=1 cut_above=1
      #1 [text=A]
        #2 [text=P]
          #3 [text=S-2]
            #4 [text=C-2]
          [omitted nodes=2 reason=node_limit]
          #7 [text=F] (focused)
            #8 [text=D1]
              #9 [text=G1]
            #10 [text=D2]
              #11 [text=G2]
          [omitted nodes=4 reason=node_limit]
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `unfit focus ancestry bundle is skipped and smaller later context still fits`() {
    val tree = node("R", node("L", node("A", node("B", node("F", focused = true)))), node("Q", node("C", node("D", node("G", focused = true)))))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT, maxNodes = 4)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=2 included=1 omitted_node_limit=1 omitted_character_limit=0
      nodes: included=4 omitted_context=2 outside_context=3 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=true character_limit=false text=false
      region: node=2 source_depth=2 cut_above=2
      #2 [text=A]
        #3 [text=B]
          #4 [text=F] (focused)
      region: node=6 source_depth=2 cut_above=2
      #6 [text=C]
        [omitted nodes=2 reason=node_limit]
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `production node cap includes parent and first ninety nine of one hundred one focused leaves`() {
    val tree = node("P", *(1..101).map { node("F$it", focused = true) }.toTypedArray())
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=101 included=99 omitted_node_limit=2 omitted_character_limit=0
      nodes: included=100 omitted_context=2 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=true character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [text=P]
        #1 [text=F1] (focused)
        #2 [text=F2] (focused)
        #3 [text=F3] (focused)
        #4 [text=F4] (focused)
        #5 [text=F5] (focused)
        #6 [text=F6] (focused)
        #7 [text=F7] (focused)
        #8 [text=F8] (focused)
        #9 [text=F9] (focused)
        #10 [text=F10] (focused)
        #11 [text=F11] (focused)
        #12 [text=F12] (focused)
        #13 [text=F13] (focused)
        #14 [text=F14] (focused)
        #15 [text=F15] (focused)
        #16 [text=F16] (focused)
        #17 [text=F17] (focused)
        #18 [text=F18] (focused)
        #19 [text=F19] (focused)
        #20 [text=F20] (focused)
        #21 [text=F21] (focused)
        #22 [text=F22] (focused)
        #23 [text=F23] (focused)
        #24 [text=F24] (focused)
        #25 [text=F25] (focused)
        #26 [text=F26] (focused)
        #27 [text=F27] (focused)
        #28 [text=F28] (focused)
        #29 [text=F29] (focused)
        #30 [text=F30] (focused)
        #31 [text=F31] (focused)
        #32 [text=F32] (focused)
        #33 [text=F33] (focused)
        #34 [text=F34] (focused)
        #35 [text=F35] (focused)
        #36 [text=F36] (focused)
        #37 [text=F37] (focused)
        #38 [text=F38] (focused)
        #39 [text=F39] (focused)
        #40 [text=F40] (focused)
        #41 [text=F41] (focused)
        #42 [text=F42] (focused)
        #43 [text=F43] (focused)
        #44 [text=F44] (focused)
        #45 [text=F45] (focused)
        #46 [text=F46] (focused)
        #47 [text=F47] (focused)
        #48 [text=F48] (focused)
        #49 [text=F49] (focused)
        #50 [text=F50] (focused)
        #51 [text=F51] (focused)
        #52 [text=F52] (focused)
        #53 [text=F53] (focused)
        #54 [text=F54] (focused)
        #55 [text=F55] (focused)
        #56 [text=F56] (focused)
        #57 [text=F57] (focused)
        #58 [text=F58] (focused)
        #59 [text=F59] (focused)
        #60 [text=F60] (focused)
        #61 [text=F61] (focused)
        #62 [text=F62] (focused)
        #63 [text=F63] (focused)
        #64 [text=F64] (focused)
        #65 [text=F65] (focused)
        #66 [text=F66] (focused)
        #67 [text=F67] (focused)
        #68 [text=F68] (focused)
        #69 [text=F69] (focused)
        #70 [text=F70] (focused)
        #71 [text=F71] (focused)
        #72 [text=F72] (focused)
        #73 [text=F73] (focused)
        #74 [text=F74] (focused)
        #75 [text=F75] (focused)
        #76 [text=F76] (focused)
        #77 [text=F77] (focused)
        #78 [text=F78] (focused)
        #79 [text=F79] (focused)
        #80 [text=F80] (focused)
        #81 [text=F81] (focused)
        #82 [text=F82] (focused)
        #83 [text=F83] (focused)
        #84 [text=F84] (focused)
        #85 [text=F85] (focused)
        #86 [text=F86] (focused)
        #87 [text=F87] (focused)
        #88 [text=F88] (focused)
        #89 [text=F89] (focused)
        #90 [text=F90] (focused)
        #91 [text=F91] (focused)
        #92 [text=F92] (focused)
        #93 [text=F93] (focused)
        #94 [text=F94] (focused)
        #95 [text=F95] (focused)
        #96 [text=F96] (focused)
        #97 [text=F97] (focused)
        #98 [text=F98] (focused)
        #99 [text=F99] (focused)
        [omitted nodes=2 reason=node_limit]
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `character cap includes exact three state only anchors with complete omission diagnostics`() {
    var tree = HierarchyNode(states = setOf("focused"))
    repeat(7) { tree = HierarchyNode(states = setOf("focused"), children = listOf(tree)) }
    val expected = """
      snapshot_id: snapshot
      focus_status: present
      focused: total=8 included=3 omitted_node_limit=0 omitted_character_limit=5
      nodes: included=3 omitted_context=5 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=true text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [] (focused)
        #1 [] (focused)
          #2 [] (focused)
            [omitted nodes=5 reason=character_limit]
    """.trimIndent() + "\n"
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT, maxCharacters = expected.length)).isEqualTo(expected)
  }

  @Test
  fun `production character cap shortens huge focused label while preserving focus and snapshot`() {
    val header = """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=1 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=true
      region: node=0 source_depth=0 cut_above=0
    """.trimIndent() + "\n"
    val prefix = "#0 [text="
    val suffix = "] [text truncated] (focused)\n"
    val expected = header + prefix + "x".repeat(12_000 - header.length - prefix.length - suffix.length) + suffix
    assertThat(McpFocusedTreeRenderer.render(node("x".repeat(20_000), focused = true), "snapshot", HierarchyFilter.CONTENT)).isEqualTo(expected)
  }

  @Test
  fun `focused text receives its budget before enormous ancestor text`() {
    val header = """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=3 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=true
      region: node=0 source_depth=0 cut_above=0
    """.trimIndent() + "\n"
    val prefix = "#0 [text="
    val suffix = "] [text truncated]\n  #1 [text=B]\n    #2 [text=focus] (focused)\n"
    val expected = header + prefix + "x".repeat(12_000 - header.length - prefix.length - suffix.length) + suffix
    val tree = node("x".repeat(20_000), node("B", node("focus", focused = true)))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT)).isEqualTo(expected)
  }

  @Test
  fun `multiple long focus labels allocate text in preorder and mark every shortened row`() {
    val header = """
      snapshot_id: snapshot
      focus_status: present
      focused: total=2 included=2 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=3 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=true
      region: node=0 source_depth=0 cut_above=0
      #0 [context]
    """.trimIndent() + "\n"
    val prefix = "  #1 [text="
    val suffix = "] [text truncated] (focused)\n  #2 [] [text truncated] (focused)\n"
    val expected = header + prefix + "x".repeat(12_000 - header.length - prefix.length - suffix.length) + suffix
    val tree = HierarchyNode(children = listOf(node("x".repeat(20_000), focused = true), node("y".repeat(20_000), focused = true)))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT)).isEqualTo(expected)
  }

  @Test
  fun `line breaks and tabs are escaped so a node always occupies one row`() {
    assertThat(McpFocusedTreeRenderer.render(node("A\r\nB\tC", focused = true), "snapshot", HierarchyFilter.CONTENT)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=1 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [text=A\r\nB\tC] (focused)
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `supplementary Unicode prefix never splits a surrogate pair at odd UTF16 budget`() {
    val header = """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=1 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=true
      region: node=0 source_depth=0 cut_above=0
    """.trimIndent() + "\n"
    val expected = header + "#0 [text=😀😀😀] [text truncated] (focused)\n"
    assertThat(McpFocusedTreeRenderer.render(node("😀".repeat(1_000), focused = true), "snapshot", HierarchyFilter.CONTENT, maxCharacters = expected.length + 1)).isEqualTo(expected)
  }

  @Test
  fun `one unit below structural fit omits next complete anchor and includes marker costs`() {
    var tree = HierarchyNode(states = setOf("focused"))
    repeat(7) { tree = HierarchyNode(states = setOf("focused"), children = listOf(tree)) }
    val three = """
      snapshot_id: snapshot
      focus_status: present
      focused: total=8 included=3 omitted_node_limit=0 omitted_character_limit=5
      nodes: included=3 omitted_context=5 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=true text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [] (focused)
        #1 [] (focused)
          #2 [] (focused)
            [omitted nodes=5 reason=character_limit]
    """.trimIndent() + "\n"
    val two = """
      snapshot_id: snapshot
      focus_status: present
      focused: total=8 included=2 omitted_node_limit=0 omitted_character_limit=6
      nodes: included=2 omitted_context=6 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=true text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [] (focused)
        #1 [] (focused)
          [omitted nodes=6 reason=character_limit]
    """.trimIndent() + "\n"
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT, maxCharacters = three.length - 1)).isEqualTo(two)
  }

  @Test
  fun `focus under ten thousand ancestors has bounded depth metadata and iterative traversal`() {
    var tree = HierarchyNode(states = setOf("focused"))
    repeat(10_000) { tree = HierarchyNode(children = listOf(tree)) }
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=3 omitted_context=0 outside_context=9998 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=9998 source_depth=9998 cut_above=9998
      #9998 [context]
        #9999 [context]
          #10000 [] (focused)
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `later admitted ancestry includes an earlier rejected focus as support without retry`() {
    val tree = HierarchyNode(states = setOf("focused"), children = listOf(HierarchyNode(states = setOf("focused"))))
    val complete = """
      snapshot_id: snapshot
      focus_status: present
      focused: total=2 included=2 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=2 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [] (focused)
        #1 [] (focused)
    """.trimIndent() + "\n"
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT, maxNodes = 2, maxCharacters = complete.length)).isEqualTo(complete)
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT, maxNodes = 1)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=2 included=1 omitted_node_limit=1 omitted_character_limit=0
      nodes: included=1 omitted_context=1 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=true character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [] (focused)
        [omitted nodes=1 reason=node_limit]
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `deep overlapping focus windows reach production character cap without orphan rows`() {
    var tree = HierarchyNode(states = setOf("focused"))
    repeat(499) { tree = HierarchyNode(states = setOf("focused"), children = listOf(tree)) }
    val response = McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT, maxNodes = 1_000)
    val rows = response.lines().filter { it.trimStart().startsWith("#") }
    assertThat(response.length <= 12_000).isTrue()
    assertThat(rows.size in 1..499).isTrue()
    assertThat(rows.map { it.trimStart().substringBefore(' ').drop(1).toInt() }).isEqualTo(rows.indices.toList())
    for ((depth, row) in rows.withIndex()) {
      assertThat(row).isEqualTo("${"  ".repeat(depth)}#$depth [] (focused)")
    }
    assertThat(response.contains("omitted_character_limit=${500 - rows.size}")).isTrue()
    assertThat(response.contains("truncated: node_limit=false character_limit=true text=false")).isTrue()
    assertThat(response.endsWith("${"  ".repeat(rows.size)}[omitted nodes=${500 - rows.size} reason=character_limit]\n")).isTrue()
  }

  @Test
  fun `state only focus indicator keeps sibling label and its immediate child`() {
    val tree = HierarchyNode(children = listOf(HierarchyNode(states = setOf("focused")), node("Play", node("Audio"))))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.CONTENT)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0
      nodes: included=4 omitted_context=0 outside_context=0 omitted_regions=0
      focused_labels_filtered: 0
      truncated: node_limit=false character_limit=false text=false
      region: node=0 source_depth=0 cut_above=0
      #0 [context]
        #1 [] (focused)
        #2 [text=Play]
          #3 [text=Audio]
      """.trimIndent() + "\n",
    )
  }

  @Test
  fun `cap omitted focus stays present and filtered labels count all original anchors`() {
    val tree = HierarchyNode(children = listOf(HierarchyNode(attributes = mapOf("accessibilityText" to "label"), states = setOf("focused")), HierarchyNode(states = setOf("focused"))))
    assertThat(McpFocusedTreeRenderer.render(tree, "snapshot", HierarchyFilter.FOCUS, maxNodes = 0)).isEqualTo(
      """
      snapshot_id: snapshot
      focus_status: present
      focused: total=2 included=0 omitted_node_limit=2 omitted_character_limit=0
      nodes: included=0 omitted_context=3 outside_context=0 omitted_regions=1
      focused_labels_filtered: 1
      truncated: node_limit=true character_limit=false text=false
      """.trimIndent() + "\n",
    )
  }

  private fun node(text: String, vararg children: HierarchyNode, focused: Boolean = false) = HierarchyNode(
    attributes = mapOf("text" to text),
    states = if (focused) setOf("focused") else emptySet(),
    children = children.toList(),
  )
}
