package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.util.UUID
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

class HierarchyDiffTest {
  private val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val beforeId = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val afterId = UUID.fromString("00000000-0000-0000-0000-000000000003")

  private fun render(before: HierarchyNode, after: HierarchyNode, afterSnapshotId: UUID = afterId): String = HierarchyDiff.render(sessionId, ResolvedHierarchySnapshotPair(beforeId, before, afterSnapshotId, after))

  private fun JsonObject.category(name: String) = getValue(name).jsonObject
  private fun JsonObject.count() = getValue("count").jsonPrimitive.content.toInt()

  private fun node(text: String) = HierarchyNode(attributes = mapOf("text" to text))
  private fun JsonObject.paths(name: String): List<String> = category(name).getValue("samples").jsonArray.map {
    it.jsonObject.getValue("text").jsonPrimitive.content.substringBefore(' ')
  }

  private val categories = listOf("added", "removed", "changed", "focus_before", "focus_after")

  private fun assertBudgets(rendered: String): JsonObject {
    val result = Json.parseToJsonElement(rendered).jsonObject
    assertThat(rendered.length <= 16_000).isTrue()
    for (name in categories) {
      val category = result.category(name)
      val samples = category.getValue("samples").jsonArray
      assertThat(samples.size <= 20).isTrue()
      assertThat(category.getValue("omitted_count").jsonPrimitive.content.toInt()).isEqualTo(category.count() - samples.size)
      for (sample in samples) {
        assertThat(sample.toString().length <= 500).isTrue()
        val text = sample.jsonObject.getValue("text").jsonPrimitive.content
        var i = 0
        var wellFormed = true
        while (i < text.length) {
          when {
            text[i].isHighSurrogate() -> {
              if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) wellFormed = false
              i += 2
            }

            text[i].isLowSurrogate() -> {
              wellFormed = false
              i++
            }

            else -> i++
          }
        }
        assertThat(wellFormed).isTrue()
      }
    }
    return result
  }

  @Test
  fun `category sample limit preserves full counts below at and above twenty`() {
    for (count in listOf(19, 20, 21)) {
      val result = assertBudgets(render(HierarchyNode(), HierarchyNode(children = List(count) { node("N$it") })))
      val added = result.category("added")
      assertThat(added.count()).isEqualTo(count)
      assertThat(added.getValue("samples").jsonArray.size).isEqualTo(minOf(count, 20))
      assertThat(added.getValue("omitted_count").jsonPrimitive.content.toInt()).isEqualTo(if (count == 21) 1 else 0)
      assertThat(added.getValue("truncated").jsonPrimitive.content).isEqualTo((count == 21).toString())
      assertThat(result.getValue("rendered_truncated").jsonPrimitive.content).isEqualTo("false")
    }
  }

  @Test
  fun `serialized sample boundaries include metadata and clipping does not omit entries`() {
    for ((length, expectedSize, truncated) in listOf(Triple(431, 499, false), Triple(432, 500, false), Triple(433, 500, true))) {
      val result = assertBudgets(render(HierarchyNode(), HierarchyNode(children = listOf(node("x".repeat(length))))))
      val added = result.category("added")
      val sample = added.getValue("samples").jsonArray.single().jsonObject
      assertThat(sample.toString().length).isEqualTo(expectedSize)
      assertThat(sample.getValue("truncated").jsonPrimitive.content).isEqualTo(truncated.toString())
      assertThat(added.getValue("truncated").jsonPrimitive.content).isEqualTo(truncated.toString())
      assertThat(added.getValue("omitted_count").jsonPrimitive.content).isEqualTo("0")
      assertThat(result.getValue("rendered_truncated").jsonPrimitive.content).isEqualTo("false")
      if (truncated) assertThat(sample.getValue("text").jsonPrimitive.content.endsWith("…")).isTrue()
    }
  }

  @Test
  fun `escaped text supplementary unicode and long paths clip into complete sample objects`() {
    val value = (34.toChar().toString() + 92.toChar() + 10.toChar() + "😀").repeat(400)
    val after = HierarchyNode(children = listOf(node(value).copy(states = setOf("focused"))))
    val escaped = assertBudgets(render(HierarchyNode(), after))
    for (name in listOf("added", "focus_after")) {
      val category = escaped.category(name)
      assertThat(category.count()).isEqualTo(1)
      assertThat(category.getValue("omitted_count").jsonPrimitive.content).isEqualTo("0")
      assertThat(category.getValue("samples").jsonArray.single().jsonObject.getValue("truncated").jsonPrimitive.content).isEqualTo("true")
    }
    fun deep(text: String): HierarchyNode {
      var tree = node(text).copy(states = setOf("focused"))
      repeat(260) { tree = HierarchyNode(children = listOf(tree)) }
      return tree
    }
    val deep = assertBudgets(render(deep("before"), deep("after")))
    assertThat(deep.category("changed").count()).isEqualTo(1)
    assertThat(deep.category("changed").getValue("samples").jsonArray.single().jsonObject.getValue("truncated").jsonPrimitive.content).isEqualTo("true")
    assertThat(deep.category("focus_before").count()).isEqualTo(1)
    assertThat(deep.category("focus_after").count()).isEqualTo(1)
  }

  @Test
  fun `global boundary keeps valid JSON at 15999 and 16000 and removes a complete lowest priority sample above`() {
    // These ASCII fixtures serialize to 15999 + marker length before applying the global cap.
    for (markerLength in 0..2) {
      val after = HierarchyNode(
        attributes = mapOf("marker" to "z".repeat(markerLength)),
        children = List(20) { node("x".repeat(303)).copy(states = setOf("focused")) },
      )
      val rendered = render(HierarchyNode(), after)
      val result = assertBudgets(rendered)
      assertThat(result.category("added").count()).isEqualTo(20)
      assertThat(result.category("changed").count()).isEqualTo(1)
      assertThat(result.category("focus_after").count()).isEqualTo(20)
      assertThat(result.category("focus_after").getValue("samples").jsonArray.size).isEqualTo(20)
      assertThat(result.getValue("rendered_truncated").jsonPrimitive.content).isEqualTo((markerLength == 2).toString())
      if (markerLength < 2) {
        assertThat(rendered.length).isEqualTo(15_999 + markerLength)
        assertThat(result.category("added").getValue("samples").jsonArray.size).isEqualTo(20)
      } else {
        assertThat(result.category("added").getValue("samples").jsonArray.size).isEqualTo(19)
        assertThat(result.category("added").getValue("omitted_count").jsonPrimitive.content).isEqualTo("1")
      }
    }
  }

  @Test
  fun `mixed worst case preserves full counts and focus priority while removing global overflow`() {
    val specialText = 34.toChar().toString() + 92.toChar() + 10.toChar() + "😀"
    fun leaves(prefix: String) = List(50) { node((prefix + it + specialText).repeat(300)).copy(states = setOf("focused")) }
    val before = HierarchyNode(
      children = listOf(
        HierarchyNode(children = leaves("before")),
        HierarchyNode(children = leaves("removed")),
        HierarchyNode(),
      ),
    )
    val after = HierarchyNode(
      children = listOf(
        HierarchyNode(children = leaves("after")),
        HierarchyNode(),
        HierarchyNode(children = leaves("added")),
      ),
    )
    val rendered = render(before, after)
    val result = assertBudgets(rendered)
    for (name in listOf("added", "removed", "changed")) {
      assertThat(result.category(name).count()).isEqualTo(50)
      assertThat(result.category(name).getValue("samples").jsonArray.size).isEqualTo(0)
      assertThat(result.category(name).getValue("truncated").jsonPrimitive.content).isEqualTo("true")
    }
    assertThat(result.category("focus_before").count()).isEqualTo(100)
    assertThat(result.category("focus_after").count()).isEqualTo(100)
    assertThat(result.category("focus_before").getValue("samples").jsonArray.size).isEqualTo(20)
    assertThat(result.category("focus_after").getValue("samples").jsonArray.isNotEmpty()).isTrue()
    assertThat(result.getValue("rendered_truncated").jsonPrimitive.content).isEqualTo("true")
    assertThat(render(before, after)).isEqualTo(rendered)
    val self = assertBudgets(render(before, before, beforeId))
    assertThat(self.category("changed").count()).isEqualTo(0)
    assertThat(self.category("focus_before").count()).isEqualTo(100)
    assertThat(self.category("focus_after").count()).isEqualTo(100)
    assertThat(self.getValue("rendered_truncated").jsonPrimitive.content).isEqualTo("true")
  }

  @Test
  fun `sample descriptions are deterministic across attribute and state ordering`() {
    val a = HierarchyNode(attributes = linkedMapOf("z" to "last", "a" to "first"), states = linkedSetOf("focused", "enabled"))
    val b = a.copy(attributes = linkedMapOf("a" to "first", "z" to "last"), states = linkedSetOf("enabled", "focused"))
    assertThat(render(HierarchyNode(), a)).isEqualTo(render(HierarchyNode(), b))
  }

  @Test
  fun `focus summaries count only directly focused nodes before and after including self comparison`() {
    val before = HierarchyNode(
      attributes = mapOf("focused" to "true"),
      children = listOf(
        node("A").copy(states = setOf("focused")),
        node("B").copy(states = setOf("focused", "selected")),
      ),
    )
    val after = HierarchyNode(states = setOf("focused"), children = listOf(node("A"), node("B")))
    val result = Json.parseToJsonElement(render(before, after)).jsonObject
    assertThat(result.category("focus_before").count()).isEqualTo(2)
    assertThat(result.paths("focus_before")).isEqualTo(listOf("/0", "/1"))
    assertThat(result.category("focus_after").count()).isEqualTo(1)
    assertThat(result.paths("focus_after")).isEqualTo(listOf("/"))
    val self = Json.parseToJsonElement(render(before, before, beforeId)).jsonObject
    assertThat(self.category("changed").count()).isEqualTo(0)
    assertThat(self.category("focus_before").count()).isEqualTo(2)
    assertThat(self.category("focus_after").count()).isEqualTo(2)
    val noFocus = Json.parseToJsonElement(render(HierarchyNode(), node("Home"))).jsonObject
    assertThat(noFocus.category("focus_before").count()).isEqualTo(0)
    assertThat(noFocus.category("focus_after").count()).isEqualTo(0)
  }

  @Test
  fun `matching paths compare every attribute and state but ignore insertion order`() {
    val before = HierarchyNode(attributes = linkedMapOf("a" to "one", "b" to "two"), states = linkedSetOf("enabled", "selected"))
    val equal = HierarchyNode(attributes = linkedMapOf("b" to "two", "a" to "one"), states = linkedSetOf("selected", "enabled"))
    assertThat(Json.parseToJsonElement(render(before, equal)).jsonObject.category("changed").count()).isEqualTo(0)
    for (after in listOf(
      before.copy(attributes = before.attributes + ("hidden" to "false")),
      before.copy(attributes = mapOf("a" to "one")),
      before.copy(attributes = before.attributes + ("b" to "updated")),
      before.copy(states = setOf("enabled")),
      before.copy(states = before.states + "focused"),
    )) {
      val result = Json.parseToJsonElement(render(before, after)).jsonObject
      assertThat(result.category("changed").count()).isEqualTo(1)
      assertThat(result.paths("changed")).isEqualTo(listOf("/"))
      assertThat(result.category("added").count()).isEqualTo(0)
      assertThat(result.category("removed").count()).isEqualTo(0)
    }
  }

  @Test
  fun `added and removed subtrees include empty structural containers in preorder`() {
    val empty = HierarchyNode()
    val expanded = HierarchyNode(children = listOf(HierarchyNode(children = listOf(node("leaf")))))
    val added = Json.parseToJsonElement(render(empty, expanded)).jsonObject
    assertThat(added.category("added").count()).isEqualTo(2)
    assertThat(added.paths("added")).isEqualTo(listOf("/0", "/0/0"))
    assertThat(added.category("changed").count()).isEqualTo(0)
    val removed = Json.parseToJsonElement(render(expanded, empty)).jsonObject
    assertThat(removed.category("removed").count()).isEqualTo(2)
    assertThat(removed.paths("removed")).isEqualTo(listOf("/0", "/0/0"))
    assertThat(removed.category("changed").count()).isEqualTo(0)
  }

  @Test
  fun `sibling insertion and reorder use positional identity`() {
    val before = HierarchyNode(children = listOf(node("A"), node("B")))
    val inserted = Json.parseToJsonElement(render(before, before.copy(children = listOf(node("X"), node("A"), node("B"))))).jsonObject
    assertThat(inserted.category("changed").count()).isEqualTo(2)
    assertThat(inserted.paths("changed")).isEqualTo(listOf("/0", "/1"))
    assertThat(inserted.category("added").count()).isEqualTo(1)
    assertThat(inserted.paths("added")).isEqualTo(listOf("/2"))
    val reordered = Json.parseToJsonElement(render(before, before.copy(children = listOf(node("B"), node("A"))))).jsonObject
    assertThat(reordered.category("changed").count()).isEqualTo(2)
    assertThat(reordered.paths("changed")).isEqualTo(listOf("/0", "/1"))
    assertThat(reordered.category("added").count()).isEqualTo(0)
    assertThat(reordered.category("removed").count()).isEqualTo(0)
  }

  @Test
  fun `self comparison keeps identities and returns empty changes`() {
    val tree = HierarchyNode(children = listOf(HierarchyNode(attributes = mapOf("text" to "Home"))))
    val result = Json.parseToJsonElement(render(tree, tree, beforeId)).jsonObject
    assertThat(result.getValue("session_id").jsonPrimitive.content).isEqualTo(sessionId.toString())
    assertThat(result.getValue("before_snapshot_id").jsonPrimitive.content).isEqualTo(beforeId.toString())
    assertThat(result.getValue("after_snapshot_id").jsonPrimitive.content).isEqualTo(beforeId.toString())
    for (name in listOf("added", "removed", "changed")) {
      assertThat(result.category(name).count()).isEqualTo(0)
      assertThat(result.category(name).getValue("samples").jsonArray.size).isEqualTo(0)
      assertThat(result.category(name).getValue("omitted_count").jsonPrimitive.content).isEqualTo("0")
      assertThat(result.category(name).getValue("truncated").jsonPrimitive.content).isEqualTo("false")
    }
  }
}
