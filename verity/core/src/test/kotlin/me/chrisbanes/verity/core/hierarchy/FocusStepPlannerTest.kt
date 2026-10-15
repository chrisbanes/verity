package me.chrisbanes.verity.core.hierarchy

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.Platform

class FocusStepPlannerTest {
  private fun card(left: Int, top: Int, focused: Boolean = false, width: Int = 300, height: Int = 100, children: List<HierarchyNode> = emptyList()) = HierarchyNode(
    attributes = mapOf("bounds" to "[$left,$top][${left + width},${top + height}]"),
    states = if (focused) setOf("focused", "clickable") else setOf("clickable"),
    children = children,
  )

  private fun container(vararg children: HierarchyNode) = HierarchyNode(children = children.toList())

  private fun bounds(node: HierarchyNode) = checkNotNull(NodeBounds.parse(node.attributes.getValue("bounds")))

  @Test fun `vertical list counts each card`() {
    val cards = List(8) { card(0, it * 100, focused = it == 0) }
    val tree = container(*cards.toTypedArray())
    assertThat(FocusStepPlanner.stepsToTarget(tree, Direction.DOWN, bounds(cards[6]))).isEqualTo(6)
    assertThat(FocusStepPlanner.stepsPastVisible(tree, Direction.DOWN)).isEqualTo(7)
    assertThat(FocusStepPlanner.stepsToTarget(tree, Direction.UP, bounds(cards[6]))).isNull()
  }

  @Test fun `rows count once per band when moving vertically`() {
    val rows = List(4) { row -> container(*Array(3) { col -> card(col * 310, row * 120, focused = row == 0 && col == 0) }) }
    val tree = container(*rows.toTypedArray())
    val target = rows[3].children[2]
    assertThat(FocusStepPlanner.stepsToTarget(tree, Direction.DOWN, bounds(target))).isEqualTo(3)
    assertThat(FocusStepPlanner.stepsPastVisible(tree, Direction.DOWN)).isEqualTo(3)
  }

  @Test fun `horizontal moves count within the focused row only`() {
    val rows = List(2) { row -> container(*Array(5) { col -> card(col * 310, row * 120, focused = row == 0 && col == 1) }) }
    val tree = container(*rows.toTypedArray())
    assertThat(FocusStepPlanner.stepsPastVisible(tree, Direction.RIGHT)).isEqualTo(3)
    assertThat(FocusStepPlanner.stepsToTarget(tree, Direction.RIGHT, bounds(rows[0].children[3]))).isEqualTo(2)
    assertThat(FocusStepPlanner.stepsPastVisible(tree, Direction.LEFT)).isEqualTo(1)
  }

  @Test fun `flat grids ignore cards outside the focused row`() {
    val tree = container(*Array(6) { card((it % 3) * 310, (it / 3) * 120, focused = it == 0) })
    assertThat(FocusStepPlanner.stepsPastVisible(tree, Direction.RIGHT)).isEqualTo(2)
  }

  @Test fun `nested clickables count the inner cards only`() {
    val row = card(0, 200, width = 1000, height = 100, children = List(3) { card(it * 310, 200) })
    val tree = container(card(0, 0, focused = true), row)
    assertThat(FocusStepPlanner.stepsPastVisible(tree, Direction.DOWN)).isEqualTo(1)
  }

  @Test fun `a target inside a card counts that card's band`() {
    val label = HierarchyNode(attributes = mapOf("text" to "Card 2", "bounds" to "[10,230][200,260]"))
    val tree = container(card(0, 0, focused = true), card(0, 100), card(0, 200, children = listOf(label)))
    assertThat(FocusStepPlanner.stepsToTarget(tree, Direction.DOWN, bounds(label))).isEqualTo(2)
  }

  @Test fun `no focus or no bounds gives no prediction`() {
    val unfocused = container(card(0, 0), card(0, 100))
    assertThat(FocusStepPlanner.stepsPastVisible(unfocused, Direction.DOWN)).isNull()
    val boundless = container(HierarchyNode(states = setOf("focused")), card(0, 100))
    assertThat(FocusStepPlanner.stepsPastVisible(boundless, Direction.DOWN)).isNull()
    val atEdge = container(card(0, 0), card(0, 100, focused = true))
    assertThat(FocusStepPlanner.stepsPastVisible(atEdge, Direction.DOWN)).isNull()
  }

  @Test fun `parses only positive bounds`() {
    assertThat(NodeBounds.parse("[0,0][100,50]")).isEqualTo(NodeBounds(0, 0, 100, 50))
    assertThat(NodeBounds.parse("[-5,0][100,50]")).isEqualTo(NodeBounds(-5, 0, 100, 50))
    for (invalid in listOf("[0,0][0,50]", "0,0,100,50", "[a,0][1,1]", "", " ", "[0,0][99999999999,1]")) {
      assertThat(NodeBounds.parse(invalid)).isNull()
    }
  }

  @Test fun `batches only a single mapped D-pad direction on Android TV`() {
    fun direction(interactions: List<Interaction>?, platform: Platform = Platform.ANDROID_TV) = FocusStepPlanner.batchDirection(interactions, platform)
    assertThat(direction(listOf(Interaction.KeyPress("Remote Dpad Down")))).isEqualTo(Direction.DOWN)
    assertThat(direction(listOf(Interaction.KeyPress("Remote Dpad Right")))).isEqualTo(Direction.RIGHT)
    assertThat(direction(listOf(Interaction.KeyPress("Remote Dpad Down"), Interaction.KeyPress("Remote Dpad Down")))).isNull()
    assertThat(direction(listOf(Interaction.KeyPress("Remote Dpad Center")))).isNull()
    assertThat(direction(listOf(Interaction.KeyCode(20)))).isNull()
    assertThat(direction(null)).isNull()
    assertThat(direction(listOf(Interaction.KeyPress("Remote Dpad Down")), Platform.ANDROID_MOBILE)).isNull()
  }
}
