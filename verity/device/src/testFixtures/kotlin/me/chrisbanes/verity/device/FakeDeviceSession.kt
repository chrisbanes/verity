package me.chrisbanes.verity.device

import java.nio.file.Path
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform

/**
 * Configurable [DeviceSession] test double for integration tests.
 * Returns canned data without requiring real device connections.
 */
class FakeDeviceSession(
  override val platform: Platform = Platform.ANDROID_TV,
  private val hierarchyNode: HierarchyNode = HierarchyNode(attributes = emptyMap()),
) : DeviceSession {
  var closed = false
  val executedFlows = mutableListOf<String>()
  val pressedKeys = mutableListOf<String>()
  val longPressedKeys = mutableListOf<String>()
  val pressedKeycodes = mutableListOf<Pair<Int, Boolean>>()

  val executedActionFlows = mutableListOf<ActionFlow>()
  override suspend fun executeActions(flow: ActionFlow): FlowResult {
    executedActionFlows += flow
    return FlowResult(success = true)
  }

  override suspend fun executeFlow(yaml: String): FlowResult {
    executedFlows += yaml
    return FlowResult(success = true)
  }

  override suspend fun pressKey(keyName: String) {
    pressedKeys += keyName
  }

  override suspend fun pressKey(keyName: String, longPress: Boolean) {
    if (platform == Platform.IOS) {
      super.pressKey(keyName, longPress)
    } else if (longPress) {
      longPressedKeys += keyName
    } else {
      pressKey(keyName)
    }
  }

  override suspend fun pressKey(keycode: Int, longPress: Boolean) {
    if (platform == Platform.IOS) {
      super.pressKey(keycode, longPress)
    } else {
      require(keycode >= 0) { "Android keycode must be non-negative" }
      pressedKeycodes += keycode to longPress
    }
  }

  override suspend fun captureHierarchyTree(): HierarchyNode = hierarchyNode

  override suspend fun captureScreenshot(output: Path) = Unit

  override suspend fun shell(command: String): String = ""

  override suspend fun waitForAnimationToEnd() = Unit

  override fun close() {
    closed = true
  }
}
