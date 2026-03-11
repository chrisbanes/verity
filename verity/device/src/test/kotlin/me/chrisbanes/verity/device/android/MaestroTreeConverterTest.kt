package me.chrisbanes.verity.device.android

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test
import maestro.TreeNode

class MaestroTreeConverterTest {
  @Test
  fun `converts empty TreeNode`() {
    val node = TreeNode()
    val result = MaestroTreeConverter.convert(node)
    assertThat(result.attributes).isEmpty()
    assertThat(result.states).isEmpty()
    assertThat(result.children).isEmpty()
  }

  @Test
  fun `converts attributes filtering empty values`() {
    val node = TreeNode(
      attributes = mutableMapOf("text" to "Hello", "empty" to "", "class" to "Button"),
    )
    val result = MaestroTreeConverter.convert(node)
    assertThat(result.attributes).isEqualTo(mapOf("text" to "Hello", "class" to "Button"))
  }

  @Test
  fun `converts boolean states`() {
    val node = TreeNode(focused = true, selected = true, checked = true, enabled = false, clickable = true)
    val result = MaestroTreeConverter.convert(node)
    assertThat(result.states).containsOnly("focused", "selected", "checked", "disabled", "clickable")
  }

  @Test
  fun `enabled true does not add disabled state`() {
    val node = TreeNode(enabled = true)
    val result = MaestroTreeConverter.convert(node)
    assertThat(result.states).isEmpty()
  }

  @Test
  fun `null booleans are ignored`() {
    val node = TreeNode(focused = null, selected = null)
    val result = MaestroTreeConverter.convert(node)
    assertThat(result.states).isEmpty()
  }

  @Test
  fun `converts children recursively`() {
    val child = TreeNode(attributes = mutableMapOf("text" to "Child"))
    val parent = TreeNode(
      attributes = mutableMapOf("text" to "Parent"),
      children = mutableListOf(child),
    )
    val result = MaestroTreeConverter.convert(parent)
    assertThat(result.children).hasSize(1)
    assertThat(result.children[0].attributes["text"]).isEqualTo("Child")
  }
}
