package me.chrisbanes.verity.device.ios

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

class IosLaunchSettleTest {
  private val switcher = HierarchyNode(
    children = listOf(HierarchyNode(attributes = mapOf("resource-id" to "SBSwitcherWindow:Main"), children = listOf(HierarchyNode(attributes = mapOf("resource-id" to "card:com.apple.Preferences:sceneID:com.apple.Preferences-default"))))),
  )
  private val settings = HierarchyNode(children = listOf(HierarchyNode(attributes = mapOf("text" to "General"))))

  @Test
  fun `waits until the launched app's switcher card leaves the hierarchy`() = runBlocking {
    val trees = ArrayDeque(listOf(switcher, switcher, settings))
    var captures = 0
    val settled = awaitIosLaunchSettled("com.apple.Preferences", {
      captures++
      trees.removeFirst()
    }, timeout = 5_000.milliseconds, poll = 1.milliseconds)
    assertThat(settled).isTrue()
    assertThat(captures).isEqualTo(3)
  }

  @Test
  fun `another app's card does not block`() = runBlocking {
    var captures = 0
    assertThat(
      awaitIosLaunchSettled("com.example.app", {
        captures++
        switcher
      }, timeout = 5_000.milliseconds),
    ).isTrue()
    assertThat(captures).isEqualTo(1)
  }

  @Test
  fun `reports an unsettled launch after the deadline`() = runBlocking {
    var captures = 0
    assertThat(
      awaitIosLaunchSettled("com.apple.Preferences", {
        captures++
        switcher
      }, timeout = 50.milliseconds, poll = 1.milliseconds),
    ).isFalse()
    assertThat(captures).isGreaterThan(1)
  }
}
