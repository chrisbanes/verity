package me.chrisbanes.verity.device.ios

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import java.io.IOException
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.device.HierarchyCaptureTimeoutException

class IosLaunchSettleTest {
  // Nested under a window node so the search must recurse.
  private val switcher = HierarchyNode(
    attributes = mapOf("resource-id" to "SBSwitcherWindow:Main"),
    children = listOf(HierarchyNode(attributes = mapOf("resource-id" to "card:com.apple.Preferences:sceneID:com.apple.Preferences-default"))),
  )
  private val settings = HierarchyNode(attributes = mapOf("text" to "General"))
  private var captures = 0

  /** Captures return [responses] in order, repeating the last; a [Throwable] response is thrown. */
  private fun settle(appId: String, vararg responses: Any, timeout: Duration = 5.seconds) = runBlocking {
    awaitIosLaunchSettled(appId, {
      when (val response = responses[minOf(captures++, responses.lastIndex)]) {
        is Throwable -> throw response
        else -> response as HierarchyNode
      }
    }, timeout, poll = 1.milliseconds)
  }

  @Test
  fun `waits until the launched app's switcher card leaves the hierarchy`() {
    settle("com.apple.Preferences", switcher, switcher, settings)
    assertThat(captures).isEqualTo(3)
  }

  @Test
  fun `another app's card does not block`() {
    settle("com.example.app", switcher)
    assertThat(captures).isEqualTo(1)
  }

  @Test
  fun `stops waiting at the deadline`() {
    settle("com.apple.Preferences", switcher, timeout = 50.milliseconds)
    assertThat(captures).isGreaterThan(1)
  }

  @Test
  fun `capture that exhausts the budget stops waiting without cancelling the caller`() {
    settle("com.apple.Preferences", switcher, HierarchyCaptureTimeoutException(), settings)
    assertThat(captures).isEqualTo(2)
  }

  @Test
  fun `failed capture is retried`() {
    settle("com.apple.Preferences", IOException("runner reset"), switcher, settings)
    assertThat(captures).isEqualTo(3)
  }
}
