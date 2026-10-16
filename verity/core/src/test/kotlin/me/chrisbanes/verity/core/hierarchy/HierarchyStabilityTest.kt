package me.chrisbanes.verity.core.hierarchy

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

class HierarchyStabilityTest {
  private fun node(vararg attributes: Pair<String, String>, states: Set<String> = emptySet(), children: List<HierarchyNode> = emptyList()) = HierarchyNode(mapOf(*attributes), states, children)

  private fun screen(child: HierarchyNode) = node("resource-id" to "root", "bounds" to "[0,0][1920,1080]", children = listOf(node("text" to "Title"), child))

  private fun equal(a: HierarchyNode, b: HierarchyNode) = isStructurallyEqual(screen(a), screen(b))

  @Test fun `identical trees are equal`() {
    val tree = node("text" to "Play", states = setOf("focused"))
    assertThat(equal(tree, tree.copy())).isTrue()
  }

  @Test fun `clock text on both sides is ignored`() {
    for ((attribute, before, after) in listOf(
      Triple("text", "00:12", "00:13"),
      Triple("accessibilityText", "1:02:03", "1:02:04"),
      Triple("text", "00:12 / 01:30", "00:13 / 01:30"),
      Triple("text", "-0:15", "-0:14"),
    )) {
      assertThat(equal(node(attribute to before), node(attribute to after)), "$before -> $after").isTrue()
    }
  }

  @Test fun `non clock text changes are structural`() {
    assertThat(equal(node("text" to "Trailer A"), node("text" to "Trailer B"))).isFalse()
    assertThat(equal(node("text" to "00:12"), node("text" to "Paused"))).isFalse()
    assertThat(equal(node("text" to "00:12"), node())).isFalse()
  }

  @Test fun `ids bounds states and children are structural`() {
    val base = node("resource-id" to "a", "bounds" to "[0,0][10,10]", states = setOf("focused"), children = listOf(node("text" to "x"), node("text" to "y")))
    for (changed in listOf(
      base.copy(attributes = base.attributes + ("resource-id" to "b")),
      base.copy(attributes = base.attributes + ("bounds" to "[0,0][10,11]")),
      base.copy(states = setOf("focused", "disabled")),
      base.copy(states = emptySet()),
      base.copy(children = base.children + node()),
      base.copy(children = base.children.take(1)),
      base.copy(children = base.children.reversed()),
    )) {
      assertThat(equal(base, changed), changed.toString()).isFalse()
    }
  }

  @Test fun `clock values under other attributes are structural`() {
    assertThat(equal(node("hintText" to "00:12"), node("hintText" to "00:13"))).isFalse()
  }

  @Test fun `a throwing checkpoint stops the comparison`() {
    class Stop : RuntimeException()
    val tree = node(children = List(100) { node("text" to "$it") })
    var checkpoints = 0
    assertFailsWith<Stop> { isStructurallyEqual(tree, tree.copy()) { if (++checkpoints == 50) throw Stop() } }
    assertThat(checkpoints).isEqualTo(50)
  }
}
