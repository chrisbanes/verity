package me.chrisbanes.verity.core.hierarchy

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class FocusDetectorTest {

  @Test
  fun `focused predicate uses only the focused state`() {
    assertThat(FocusDetector.isFocused(HierarchyNode(states = setOf("focused")))).isTrue()
    assertThat(FocusDetector.isFocused(HierarchyNode(attributes = mapOf("focused" to "true")))).isFalse()
    assertThat(FocusDetector.isFocused(HierarchyNode(states = setOf("selected")))).isFalse()
    assertThat(FocusDetector.isFocused(HierarchyNode())).isFalse()
  }

  @Test
  fun `tree focus preserves leaf container descendant ancestor and sibling relationships`() {
    val text = HierarchyNode(attributes = mapOf("text" to "Home"))
    val focused = HierarchyNode(states = setOf("focused"))
    val fixtures = listOf(
      text.copy(states = setOf("focused")),
      focused.copy(children = listOf(text)),
      focused.copy(children = listOf(HierarchyNode(children = listOf(text)))),
      text.copy(children = listOf(focused)),
      HierarchyNode(children = listOf(focused, text)),
    )
    for (tree in fixtures) {
      assertThat(FocusDetector.containsFocused(tree, "home")).isTrue()
    }
    assertThat(FocusDetector.containsFocused(text, "Home")).isFalse()
    assertThat(FocusDetector.containsFocused(text.copy(attributes = text.attributes + ("focused" to "true")), "Home")).isFalse()
    assertThat(FocusDetector.containsFocused(HierarchyNode(children = listOf(HierarchyNode(children = listOf(focused)), HierarchyNode(children = listOf(text)))), "Home")).isFalse()
  }

  @Test
  fun `text on focused node`() {
    val hierarchy = """
            [text=Home] (focused)
            [text=Settings]
    """.trimIndent()
    assertThat(FocusDetector.containsFocused(hierarchy, "Home")).isTrue()
  }

  @Test
  fun `text not on focused node`() {
    val hierarchy = """
            [text=Home] (focused)
            [text=Settings]
    """.trimIndent()
    assertThat(FocusDetector.containsFocused(hierarchy, "Settings")).isFalse()
  }

  @Test
  fun `text on descendant of focused node`() {
    val hierarchy = """
            [resource-id=card] (focused)
              [text=Movie Title]
              [text=2024]
    """.trimIndent()
    assertThat(FocusDetector.containsFocused(hierarchy, "Movie Title")).isTrue()
  }

  @Test
  fun `text on sibling of focused node`() {
    val hierarchy = """
            [resource-id=container]
              [resource-id=focus-indicator] (focused)
              [text=Movie Title]
    """.trimIndent()
    assertThat(FocusDetector.containsFocused(hierarchy, "Movie Title")).isTrue()
  }

  @Test
  fun `text on ancestor of focused node is not a match`() {
    val hierarchy = """
            [text=Row Title]
              [resource-id=item] (focused)
    """.trimIndent()
    assertThat(FocusDetector.containsFocused(hierarchy, "Row Title")).isTrue()
  }

  @Test
  fun `no focused node returns false`() {
    val hierarchy = """
            [text=Home]
            [text=Settings]
    """.trimIndent()
    assertThat(FocusDetector.containsFocused(hierarchy, "Home")).isFalse()
  }

  @Test
  fun `case insensitive text matching`() {
    val hierarchy = "[text=Home] (focused)"
    assertThat(FocusDetector.containsFocused(hierarchy, "home")).isTrue()
  }

  @Test
  fun `empty hierarchy returns false`() {
    assertThat(FocusDetector.containsFocused("", "anything")).isFalse()
  }

  @Test
  fun `deeply nested descendant of focused node`() {
    val hierarchy = """
            [resource-id=card] (focused)
              [resource-id=inner]
                [text=Deep Text]
    """.trimIndent()
    assertThat(FocusDetector.containsFocused(hierarchy, "Deep Text")).isTrue()
  }
}
