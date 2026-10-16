package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession
import me.chrisbanes.verity.device.FocusChangeObserver
import me.chrisbanes.verity.device.StableHierarchyResult

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KeySettleTest {
  private fun TestScope.observer() = FocusChangeObserver { testScheduler.currentTime * 1_000_000 }

  private fun focused(id: String) = HierarchyNode(attributes = mapOf("resource-id" to id), states = setOf("focused"))

  private var captures = 0

  /**
   * Focus moves with every key press when [changes], else stays put. [trees] scripts each capture
   * by index instead.
   */
  private fun session(
    platform: Platform = Platform.ANDROID_TV,
    changes: Boolean = true,
    baselineFails: Boolean = false,
    trees: List<HierarchyNode>? = null,
  ): FakeDeviceSession {
    lateinit var fake: FakeDeviceSession
    fun presses() = fake.events.count { it.startsWith("name:") || it.startsWith("key:") }
    fake = FakeDeviceSession(platform, hierarchyProvider = {
      captures++
      if (baselineFails && presses() == 0) error("capture failed")
      trees?.get(captures - 1) ?: focused(if (changes) "node-${presses()}" else "node-0")
    })
    return fake
  }

  private suspend fun TestScope.run(session: DeviceSession, interaction: Interaction, visual: Boolean = false) = InteractionExecutor(session, "app", observer()).execute(interaction, visual)

  private val down = Interaction.KeyPress("Remote Dpad Down")
  private val center = Interaction.KeyPress("Remote Dpad Center")

  @Test fun `named direction skips settle when focus changes`() = runTest {
    val s = session()
    run(s, down)
    assertThat(s.events).isEqualTo(listOf("name:Remote Dpad Down"))
  }

  @Test fun `named direction with focus unchanged settles on stability`() = runTest {
    val s = session(changes = false)
    val settled = s.pressAndSettle(observer(), direction = true) { s.pressKey("Remote Dpad Down") }
    assertThat(s.events).isEqualTo(listOf("name:Remote Dpad Down"))
    // The focus result stays null, so loop batching still treats this as an edge.
    assertThat(settled).isEqualTo(KeySettleResult(focus = null, tree = focused("node-0")))
  }

  @Test fun `non direction keys settle on two equal captures`() = runTest {
    for (key in listOf("Remote Dpad Center", "back")) {
      captures = 0
      val s = session()
      val tree = run(s, Interaction.KeyPress(key))
      assertThat(s.events).isEqualTo(listOf("name:$key"))
      assertThat(captures).isEqualTo(2)
      assertThat(tree).isEqualTo(focused("node-1"))
    }
  }

  @Test fun `keycode settles only once consecutive captures match`() = runTest {
    val a = focused("a")
    val b = focused("b")
    val s = session(trees = listOf(a, b, b))
    val tree = run(s, Interaction.KeyCode(127))
    assertThat(s.events).isEqualTo(listOf("key:127:false"))
    assertThat(captures).isEqualTo(3)
    assertThat(tree).isEqualTo(b)
  }

  @Test fun `capture failure falls back to the animation wait`() = runTest {
    val s = FakeDeviceSession(Platform.ANDROID_TV)
    assertThat(run(s, center)).isNull()
    assertThat(s.events).isEqualTo(listOf("name:Remote Dpad Center", "settle"))
  }

  @Test fun `budget expiry proceeds without a wait or a tree`() = runTest {
    val fake = session()
    val expiring = object : DeviceSession by fake {
      override suspend fun awaitStableHierarchy(budget: Duration) = StableHierarchyResult(stable = false, timedOut = true, elapsedMs = budget.inWholeMilliseconds, tree = null)
    }
    assertThat(run(expiring, center)).isNull()
    assertThat(fake.events).isEqualTo(listOf("name:Remote Dpad Center"))
  }

  @Test fun `visual settles keep the animation wait without capturing`() = runTest {
    for (key in listOf(center, down)) {
      val s = session()
      assertThat(run(s, key, visual = true)).isNull()
      assertThat(s.events).isEqualTo(listOf("name:${key.keyName}", "settle"))
    }
    val keycode = session()
    run(keycode, Interaction.KeyCode(20), visual = true)
    assertThat(keycode.events).isEqualTo(listOf("key:20:false", "settle"))
    // The direction presses would change focus, but a visual settle never observes it.
    assertThat(captures).isEqualTo(0)
  }

  @Test fun `direction keycode skips settle only on change`() = runTest {
    val changed = session()
    run(changed, Interaction.KeyCode(20))
    assertThat(changed.events).isEqualTo(listOf("key:20:false"))
    val unchanged = session(changes = false)
    run(unchanged, Interaction.KeyCode(20))
    assertThat(unchanged.events).isEqualTo(listOf("key:20:false"))
  }

  @Test fun `long press and non direction keycode settle on stability`() = runTest {
    val long = session()
    run(long, Interaction.KeyCode(20, longPress = true))
    assertThat(long.events).isEqualTo(listOf("key:20:true"))
    val enter = session()
    run(enter, Interaction.KeyCode(66))
    assertThat(enter.events).isEqualTo(listOf("key:66:false"))
  }

  @Test fun `non TV platforms keep settle`() = runTest {
    val mobile = session(Platform.ANDROID_MOBILE)
    run(mobile, down)
    run(mobile, Interaction.KeyCode(127))
    assertThat(mobile.events).isEqualTo(listOf("name:Remote Dpad Down", "settle", "key:127:false", "settle"))
    val ios = session(Platform.IOS)
    run(ios, center)
    assertThat(ios.events).isEqualTo(listOf("name:Remote Dpad Center", "settle"))
    assertThat(captures).isEqualTo(0)
  }

  @Test fun `baseline failure presses once and settles on stability`() = runTest {
    val s = session(baselineFails = true)
    run(s, down)
    assertThat(s.events).isEqualTo(listOf("name:Remote Dpad Down"))
  }

  private suspend fun TestScope.ordered(s: FakeDeviceSession, keycode: Int) = s.executeOrdered(
    ActionFlow("app", listOf(Interaction.TapOnText("x"), Interaction.KeyCode(keycode), Interaction.TapOnText("y"))),
    observer(),
  )

  @Test fun `ordered keycode skips settle on change`() = runTest {
    val s = session()
    ordered(s, 20)
    assertThat(s.events).isEqualTo(listOf("flow", "key:20:false", "flow"))
  }

  @Test fun `ordered keycode settles on stability when unchanged`() = runTest {
    val s = session(changes = false)
    ordered(s, 20)
    assertThat(s.events).isEqualTo(listOf("flow", "key:20:false", "flow"))
  }

  @Test fun `ordered non direction keycode settles on stability`() = runTest {
    val s = session()
    ordered(s, 66)
    assertThat(s.events).isEqualTo(listOf("flow", "key:66:false", "flow"))
  }

  @Test fun `ordered stable settle supplies the next press's focus baseline`() = runTest {
    val s = session()
    s.executeOrdered(ActionFlow("app", listOf(Interaction.KeyCode(127), down)), observer())
    assertThat(s.events).isEqualTo(listOf("key:127:false", "name:Remote Dpad Down"))
    // Two stability captures, then one change observation with no baseline capture.
    assertThat(captures).isEqualTo(3)
  }

  private val wait = Interaction.WaitForAnimation()

  private suspend fun TestScope.navigatorFlow(s: FakeDeviceSession) = s.executeOrdered(
    ActionFlow("app", listOf(down, wait, down, wait, center, wait)),
    observer(),
  )

  @Test fun `ordered named direction replaces following wait with focus settle`() = runTest {
    val s = session()
    navigatorFlow(s)
    assertThat(s.events).isEqualTo(listOf("name:Remote Dpad Down", "name:Remote Dpad Down", "flow"))
    assertThat(s.executedActionFlows.single().actions).isEqualTo(listOf(center, wait))
  }

  @Test fun `ordered named direction settles on stability when focus unchanged`() = runTest {
    val s = session(changes = false)
    navigatorFlow(s)
    assertThat(s.events).isEqualTo(listOf("name:Remote Dpad Down", "name:Remote Dpad Down", "flow"))
  }

  @Test fun `ordered named direction stays in maestro off TV`() = runTest {
    val s = session(Platform.ANDROID_MOBILE)
    navigatorFlow(s)
    assertThat(s.events).isEqualTo(listOf("flow"))
    assertThat(s.executedActionFlows.single().actions).isEqualTo(listOf(down, wait, down, wait, center, wait))
  }

  @Test fun `ordered keeps wait that does not follow a direct press`() = runTest {
    val s = session()
    s.executeOrdered(ActionFlow("app", listOf(wait, down, center, wait)), observer())
    assertThat(s.events).isEqualTo(listOf("flow", "name:Remote Dpad Down", "flow"))
    assertThat(s.executedActionFlows.map { it.actions }).isEqualTo(listOf(listOf(wait), listOf(center, wait)))
  }

  @Test fun `consecutive direct presses reuse the previous focus as baseline`() = runTest {
    val s = session()
    navigatorFlow(s)
    // One baseline plus one change observation per press.
    assertThat(captures).isEqualTo(3)
  }

  @Test fun `a maestro sub flow between presses forces a fresh baseline`() = runTest {
    val s = session()
    s.executeOrdered(ActionFlow("app", listOf(down, center, down)), observer())
    assertThat(s.events).isEqualTo(listOf("name:Remote Dpad Down", "flow", "name:Remote Dpad Down"))
    assertThat(captures).isEqualTo(4)
  }
}
