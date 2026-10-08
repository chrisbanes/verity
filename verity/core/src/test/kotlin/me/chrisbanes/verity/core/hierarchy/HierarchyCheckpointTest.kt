package me.chrisbanes.verity.core.hierarchy

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

class HierarchyCheckpointTest {
  private class Stop : RuntimeException()

  @Test fun `literal attribute traversal and rendering can be stopped inside one wide node`() {
    val node = HierarchyNode((0 until 1000).associate { "text$it" to "value$it" })
    for (operation in listOf<(() -> Unit) -> Unit>(
      { checkpoint -> node.containsText("absent", checkpoint = checkpoint) },
      { checkpoint -> HierarchyRenderer.render(node, HierarchyFilter.ALL, checkpoint) },
    )) {
      var checkpoints = 0
      assertFailsWith<Stop> { operation { if (++checkpoints == 50) throw Stop() } }
      assertThat(checkpoints).isEqualTo(50)
    }
  }

  @Test fun `focus relationship loops remain cooperative after flattening completes`() {
    val node = HierarchyNode(
      children = (0 until 50).map { index ->
        HierarchyNode(
          children = listOf(
            HierarchyNode(
              attributes = mapOf("text" to if (index % 2 == 0) "needle" else "other"),
              states = if (index % 2 != 0) setOf("focused") else emptySet(),
            ),
          ),
        )
      },
    )
    var checkpoints = 0
    assertFailsWith<Stop> { FocusDetector.containsFocused(node, "needle") { if (++checkpoints == 400) throw Stop() } }
    assertThat(checkpoints).isEqualTo(400)
  }

  @Test fun `default helpers and checkpointed helpers produce identical results`() {
    val node = HierarchyNode(children = listOf(HierarchyNode(mapOf("text" to "Settings"), setOf("focused", "selected"))))
    assertThat(node.containsText("settings")).isEqualTo(node.containsText("settings", checkpoint = {}))
    assertThat(FocusDetector.containsFocused(node, "Settings")).isEqualTo(FocusDetector.containsFocused(node, "Settings") {})
    assertThat(HierarchyRenderer.render(node, HierarchyFilter.CONTENT)).isEqualTo(HierarchyRenderer.render(node, HierarchyFilter.CONTENT) {})
  }
}
