package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.FakeDeviceSession
import me.chrisbanes.verity.device.FocusChangeObserver

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KeySettleTest {
  private fun TestScope.observer() = FocusChangeObserver { testScheduler.currentTime * 1_000_000 }

  private fun focused(id: String) = HierarchyNode(attributes = mapOf("resource-id" to id), states = setOf("focused"))

  /** Focus is "a" until a key is pressed, then "b" when [changes], else stays "a". */
  private fun session(
    platform: Platform = Platform.ANDROID_TV,
    changes: Boolean = true,
    baselineFails: Boolean = false,
  ): FakeDeviceSession {
    lateinit var fake: FakeDeviceSession
    fun pressed() = fake.events.any { it.startsWith("name:") || it.startsWith("key:") }
    fake = FakeDeviceSession(platform, hierarchyProvider = {
      if (baselineFails && !pressed()) error("capture failed")
      focused(if (changes && pressed()) "b" else "a")
    })
    return fake
  }

  private suspend fun TestScope.run(session: FakeDeviceSession, interaction: Interaction) = InteractionExecutor(session, "app", observer()).execute(interaction)

  private val down = Interaction.KeyPress("Remote Dpad Down")

  @Test fun `named direction skips settle when focus changes`() = runTest {
    val s = session()
    run(s, down)
    assertThat(s.events).isEqualTo(listOf("name:Remote Dpad Down"))
  }

  @Test fun `named direction settles when focus unchanged`() = runTest {
    val s = session(changes = false)
    run(s, down)
    assertThat(s.events).isEqualTo(listOf("name:Remote Dpad Down", "settle"))
  }

  @Test fun `non direction keys keep settle without capturing`() = runTest {
    for (key in listOf("Remote Dpad Center", "back")) {
      val s = session()
      run(s, Interaction.KeyPress(key))
      assertThat(s.events).isEqualTo(listOf("name:$key", "settle"))
    }
  }

  @Test fun `direction keycode skips settle only on change`() = runTest {
    val changed = session()
    run(changed, Interaction.KeyCode(20))
    assertThat(changed.events).isEqualTo(listOf("key:20:false"))
    val unchanged = session(changes = false)
    run(unchanged, Interaction.KeyCode(20))
    assertThat(unchanged.events).isEqualTo(listOf("key:20:false", "settle"))
  }

  @Test fun `long press and non direction keycode keep settle`() = runTest {
    val long = session()
    run(long, Interaction.KeyCode(20, longPress = true))
    assertThat(long.events).isEqualTo(listOf("key:20:true", "settle"))
    val enter = session()
    run(enter, Interaction.KeyCode(66))
    assertThat(enter.events).isEqualTo(listOf("key:66:false", "settle"))
  }

  @Test fun `non TV platform keeps settle`() = runTest {
    val s = session(Platform.ANDROID_MOBILE)
    run(s, down)
    assertThat(s.events).isEqualTo(listOf("name:Remote Dpad Down", "settle"))
  }

  @Test fun `baseline failure presses once and settles`() = runTest {
    val s = session(baselineFails = true)
    run(s, down)
    assertThat(s.events).isEqualTo(listOf("name:Remote Dpad Down", "settle"))
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

  @Test fun `ordered keycode settles when unchanged`() = runTest {
    val s = session(changes = false)
    ordered(s, 20)
    assertThat(s.events).isEqualTo(listOf("flow", "key:20:false", "settle", "flow"))
  }

  @Test fun `ordered non direction keycode settles`() = runTest {
    val s = session()
    ordered(s, 66)
    assertThat(s.events).isEqualTo(listOf("flow", "key:66:false", "settle", "flow"))
  }
}
