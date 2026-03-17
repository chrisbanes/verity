package me.chrisbanes.verity.device

import java.nio.file.Path
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
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

  override suspend fun executeFlow(yaml: String): FlowResult {
    executedFlows += yaml
    return FlowResult(success = true)
  }

  override suspend fun pressKey(keyName: String) {
    pressedKeys += keyName
  }

  override suspend fun captureHierarchyTree(): HierarchyNode = hierarchyNode

  override suspend fun captureScreenshot(output: Path) = Unit

  override suspend fun shell(command: String): String = ""

  override suspend fun waitForAnimationToEnd() = Unit

  override fun close() {
    closed = true
  }
}
