package me.chrisbanes.verity.device.ios

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.IOException
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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

  /** Captures return [responses] in order, repeating the last; a [Throwable] response is thrown. Uses virtual time. */
  private suspend fun TestScope.settle(appId: String, vararg responses: Any) = awaitIosLaunchSettled(
    appId,
    {
      when (val response = responses[minOf(captures++, responses.lastIndex)]) {
        is Throwable -> throw response
        else -> response as HierarchyNode
      }
    },
    timeout = 1.seconds,
    timeSource = testScheduler.timeSource,
  )

  @Test
  fun `waits until the launched app's switcher card leaves the hierarchy`() = runTest {
    settle("com.apple.Preferences", switcher, switcher, settings)
    assertThat(captures).isEqualTo(3)
  }

  @Test
  fun `another app's card does not block`() = runTest {
    settle("com.example.app", switcher)
    assertThat(captures).isEqualTo(1)
  }

  @Test
  fun `stops waiting at the deadline`() = runTest {
    settle("com.apple.Preferences", switcher)
    // Captures at 0, 250, 500 and 750 ms; the deadline passes at the 1 s check.
    assertThat(captures).isEqualTo(4)
  }

  @Test
  fun `capture that exhausts the budget stops waiting without cancelling the caller`() = runTest {
    settle("com.apple.Preferences", switcher, HierarchyCaptureTimeoutException(), settings)
    assertThat(captures).isEqualTo(2)
  }

  @Test
  fun `failed capture is retried`() = runTest {
    settle("com.apple.Preferences", IOException("runner reset"), switcher, settings)
    assertThat(captures).isEqualTo(3)
  }
}
