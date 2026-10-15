package me.chrisbanes.verity.core.hierarchy

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import kotlin.test.Test

class HierarchyRendererTest {

  private fun node(
    attributes: Map<String, String> = emptyMap(),
    states: Set<String> = emptySet(),
    children: List<HierarchyNode> = emptyList(),
  ) = HierarchyNode(attributes, states, children)

  @Test
  fun `renders single node with text`() {
    val tree = node(attributes = mapOf("text" to "Home"))
    val result = HierarchyRenderer.render(tree, HierarchyFilter.CONTENT)
    assertThat(result).contains("[text=Home]")
  }

  @Test
  fun `renders states as suffix`() {
    val tree = node(
      attributes = mapOf("text" to "Settings"),
      states = setOf("focused", "clickable"),
    )
    val result = HierarchyRenderer.render(tree, HierarchyFilter.CONTENT)
    assertThat(result).contains("(clickable,focused)")
  }

  @Test
  fun `renders children indented`() {
    val tree = node(
      attributes = mapOf("text" to "Nav"),
      children = listOf(
        node(attributes = mapOf("text" to "Home")),
        node(attributes = mapOf("text" to "Settings")),
      ),
    )
    val result = HierarchyRenderer.render(tree, HierarchyFilter.CONTENT)
    assertThat(result).contains("  [text=Home]")
    assertThat(result).contains("  [text=Settings]")
  }

  @Test
  fun `FOCUS filter excludes bounds`() {
    val tree = node(
      attributes = mapOf("text" to "Home", "bounds" to "[0,0][100,50]"),
    )
    val result = HierarchyRenderer.render(tree, HierarchyFilter.FOCUS)
    assertThat(result).doesNotContain("bounds")
  }

  @Test
  fun `CONTENT filter includes bounds`() {
    val tree = node(
      attributes = mapOf("text" to "Home", "bounds" to "[0,0][100,50]"),
    )
    val result = HierarchyRenderer.render(tree, HierarchyFilter.CONTENT)
    assertThat(result).contains("bounds")
  }

  @Test
  fun `ALL filter includes everything`() {
    val tree = node(
      attributes = mapOf("text" to "X", "class" to "android.widget.TextView"),
    )
    val result = HierarchyRenderer.render(tree, HierarchyFilter.ALL)
    assertThat(result).contains("class=android.widget.TextView")
  }

  @Test
  fun `strips empty string attributes`() {
    val tree = node(
      attributes = mapOf("text" to "Home", "resource-id" to ""),
    )
    val result = HierarchyRenderer.render(tree, HierarchyFilter.ALL)
    assertThat(result).doesNotContain("resource-id")
  }

  @Test
  fun `collapses empty container with single child`() {
    val container = node(
      children = listOf(
        node(attributes = mapOf("text" to "Home")),
      ),
    )
    val result = HierarchyRenderer.render(container, HierarchyFilter.CONTENT)
    // Child should render at depth 0, not indented under empty container
    assertThat(result.trimEnd()).isEqualTo("[text=Home]")
  }

  private val full = "[0,0][1920,1080]"

  private fun bounds(b: String = full) = mapOf("bounds" to b)

  private fun lines(tree: HierarchyNode, filter: HierarchyFilter) = HierarchyRenderer.render(tree, filter).lines().map { it.trim() }.filter { it.isNotEmpty() }

  @Test
  fun `PROMPT collapses bounds-only single-child chain while CONTENT keeps it`() {
    val tree = node(
      bounds(),
      children = listOf(
        node(
          bounds("[0,0][1000,500]"),
          children = listOf(node(mapOf("text" to "Home", "bounds" to "[10,10][100,50]"))),
        ),
      ),
    )
    assertThat(HierarchyRenderer.render(tree, HierarchyFilter.PROMPT))
      .isEqualTo("[text=Home, bounds=[10,10][100,50]]\n")
    assertThat(lines(tree, HierarchyFilter.CONTENT).size).isEqualTo(3)
  }

  @Test
  fun `PROMPT omits bounds-only leaf`() {
    val tree = node(
      attributes = mapOf("text" to "Nav"),
      children = listOf(node(bounds())),
    )
    assertThat(HierarchyRenderer.render(tree, HierarchyFilter.PROMPT)).isEqualTo("[text=Nav]\n")
  }

  @Test
  fun `PROMPT keeps bounds-only grouping node with two children`() {
    val tree = node(
      bounds("[0,0][500,100]"),
      children = listOf(
        node(mapOf("text" to "A")),
        node(mapOf("text" to "B")),
      ),
    )
    assertThat(HierarchyRenderer.render(tree, HierarchyFilter.PROMPT))
      .isEqualTo("[bounds=[0,0][500,100]]\n  [text=A]\n  [text=B]\n")
  }

  @Test
  fun `PROMPT keeps bounds-only nodes with focused or clickable state`() {
    for (state in listOf("focused", "clickable")) {
      val tree = node(bounds(), setOf(state))
      assertThat(HierarchyRenderer.render(tree, HierarchyFilter.PROMPT)).isEqualTo("[bounds=$full] ($state)\n")
    }
  }

  @Test
  fun `PROMPT drops accessibilityText equal to text only`() {
    val same = node(mapOf("text" to "Play", "accessibilityText" to "Play", "bounds" to "[0,0][1,1]"))
    assertThat(HierarchyRenderer.render(same, HierarchyFilter.PROMPT)).isEqualTo("[text=Play, bounds=[0,0][1,1]]\n")
    val different = node(mapOf("text" to "Play", "accessibilityText" to "Play button", "bounds" to "[0,0][1,1]"))
    assertThat(HierarchyRenderer.render(different, HierarchyFilter.PROMPT)).contains("accessibilityText=Play button")
    assertThat(HierarchyRenderer.render(same, HierarchyFilter.CONTENT)).contains("accessibilityText=Play")
  }

  @Test
  fun `PROMPT lines are a subset of CONTENT lines and keep every stateful node`() {
    val tree = node(
      bounds(),
      children = listOf(
        node(
          bounds("[0,0][1000,500]"),
          children = listOf(
            node(
              bounds("[0,0][1000,100]"),
              children = listOf(
                node(mapOf("text" to "Row1", "accessibilityText" to "Row1", "bounds" to "[0,0][500,100]"), setOf("focused")),
                node(bounds("[500,0][1000,100]"), setOf("clickable")),
                node(bounds("[0,0][1,1]")),
              ),
            ),
          ),
        ),
        node(mapOf("resource-id" to "id/x", "bounds" to "[0,500][1000,600]")),
      ),
    )
    val content = lines(tree, HierarchyFilter.CONTENT)
    val prompt = lines(tree, HierarchyFilter.PROMPT)
    val boundsRegex = Regex("bounds=(\\[\\d+,\\d+]\\[\\d+,\\d+])")
    val contentBounds = content.flatMap { l -> boundsRegex.findAll(l).map { it.groupValues[1] } }.toSet()
    val promptBounds = prompt.flatMap { l -> boundsRegex.findAll(l).map { it.groupValues[1] } }.toSet()
    assertThat(promptBounds.all { it in contentBounds }).isEqualTo(true)
    assertThat(prompt.size < content.size).isEqualTo(true)
    assertThat(prompt.any { it.contains("(focused)") }).isEqualTo(true)
    assertThat(prompt.any { it.contains("(clickable)") }).isEqualTo(true)
    // Only accessibilityText dedup may change a kept line; here Row1 is the sole such line.
    val contentSet = content.map { it.replace(", accessibilityText=Row1", "") }.toSet()
    assertThat(prompt.all { it in contentSet }).isEqualTo(true)
  }
}
