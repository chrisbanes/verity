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
}
