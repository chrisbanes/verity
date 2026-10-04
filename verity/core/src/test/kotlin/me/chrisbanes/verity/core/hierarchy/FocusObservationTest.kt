package me.chrisbanes.verity.core.hierarchy

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test

class FocusObservationTest {
  private fun node(id: String? = null, focus: Boolean = false, children: List<HierarchyNode> = emptyList()) = HierarchyNode(attributes = id?.let { mapOf("resource-id" to it) }.orEmpty(), states = if (focus) setOf("focused") else emptySet(), children = children)
  private fun changed(a: HierarchyNode, b: HierarchyNode) = hasFocusChanged(observeFocus(a), observeFocus(b))

  @Test fun `preorder paths include containers and normalize blank resource IDs`() {
    val tree = node(" root ", true, listOf(node(children = listOf(node(" ", true))), node("same", true), node("same")))
    val observation = observeFocus(tree)
    assertThat(observation.focusedNodes).isEqualTo(listOf(FocusedNode("/", "root"), FocusedNode("/0/0", null), FocusedNode("/1", "same")))
    assertThat(observation.resourceIdCounts).isEqualTo(mapOf("root" to 1, "same" to 2))
    assertThat(observeFocus(HierarchyNode(attributes = mapOf("focused" to "true"))).focusedNodes).isEqualTo(emptyList())
  }

  @Test fun `unique resource can move but duplicate resource switches require paths`() {
    assertThat(changed(node(children = listOf(node("id", true))), node(children = listOf(node(), node("id", true))))).isEqualTo(false)
    assertThat(changed(node(children = listOf(node("id", true), node("id"))), node(children = listOf(node("id"), node("id", true))))).isEqualTo(true)
    assertThat(changed(node(children = listOf(node(focus = true))), node(children = listOf(node(), node(focus = true))))).isEqualTo(true)
  }

  @Test fun `nonfocused duplicate introduction and removal alone preserve identity`() {
    val one = node(children = listOf(node("id", true)))
    val two = one.copy(children = one.children + node("id"))
    assertThat(changed(one, two)).isEqualTo(false)
    assertThat(changed(two, one)).isEqualTo(false)
  }

  @Test fun `empty transitions set gains losses and typed identity collisions`() {
    val empty = node()
    val focused = node("id", true)
    assertThat(changed(empty, focused)).isEqualTo(true)
    assertThat(changed(focused, empty)).isEqualTo(true)
    assertThat(changed(empty, empty)).isEqualTo(false)
    val both = node(children = listOf(node("a", true), node("b", true)))
    assertThat(changed(both, both.copy(children = both.children.reversed()))).isEqualTo(false)
    assertThat(changed(both, both.copy(children = listOf(node("a", true), node("b"))))).isEqualTo(true)
    assertThat(changed(focused, node("id", true, listOf(node("b", true))))).isEqualTo(true)
    assertThat(changed(node("/", true), node(focus = true))).isEqualTo(true)
  }

  @Test fun `text attributes and map state iteration do not imply focus change`() {
    val a = node("id", true).copy(attributes = mapOf("resource-id" to "id", "text" to "a"), states = linkedSetOf("focused", "selected"))
    val b = a.copy(attributes = linkedMapOf("text" to "b", "resource-id" to "id"), states = linkedSetOf("selected", "focused"), children = listOf(node("other")))
    assertThat(changed(a, b)).isEqualTo(false)
  }

  @Test fun `checkpoint stops exact traversal and identity work`() {
    var visits = 0
    assertFailure { observeFocus(node(children = List(10) { node() })) { if (++visits == 4) error("stop") } }.isInstanceOf<IllegalStateException>()
    assertThat(visits).isEqualTo(4)
    val observation = observeFocus(node(children = List(10) { node(it.toString(), true) }))
    visits = 0
    assertFailure { hasFocusChanged(observation, observation) { if (++visits == 3) error("stop") } }.isInstanceOf<IllegalStateException>()
    assertThat(visits).isEqualTo(3)
  }
}
