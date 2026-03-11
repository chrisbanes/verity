package me.chrisbanes.verity.device.ios

import java.nio.file.Path
import maestro.Maestro
import me.chrisbanes.verity.core.hierarchy.FocusDetector
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession

/**
 * iOS device session backed by Maestro's XCTest HTTP client.
 *
 * The [maestro] instance wraps an [maestro.drivers.IOSDriver] which communicates
 * with the on-device XCTest runner over HTTP (port 22087).
 */
class IosDeviceSession(
  private val maestro: Maestro,
  private val iosDevice: device.IOSDevice,
) : DeviceSession {

  override val platform: Platform = Platform.IOS

  override suspend fun executeFlow(yaml: String): FlowResult {
    TODO("Wire Maestro iOS flow execution — requires maestro-orchestra dependency")
  }

  override suspend fun pressKey(keyName: String) {
    iosDevice.pressKey(keyName)
  }

  override suspend fun captureHierarchyTree(filter: HierarchyFilter): HierarchyNode {
    val hierarchy = iosDevice.viewHierarchy(false)
    return XcTestTreeConverter.convert(hierarchy.axElement)
  }

  override suspend fun captureHierarchy(filter: HierarchyFilter): String {
    val tree = captureHierarchyTree(filter)
    return HierarchyRenderer.render(tree, filter)
  }

  @Suppress("DEPRECATION")
  override suspend fun captureScreenshot(output: Path) {
    maestro.takeScreenshot(output.toFile(), false)
  }

  override suspend fun containsText(text: String, ignoreCase: Boolean): Boolean {
    val hierarchy = captureHierarchy(HierarchyFilter.CONTENT)
    return if (ignoreCase) {
      hierarchy.lowercase().contains(text.lowercase())
    } else {
      hierarchy.contains(text)
    }
  }

  override suspend fun checkFocused(text: String): Boolean {
    val hierarchy = captureHierarchy(HierarchyFilter.FOCUS)
    return FocusDetector.containsFocused(hierarchy, text)
  }

  override suspend fun shell(command: String): String {
    TODO("Wire iOS shell command (xcrun simctl or devicectl)")
  }

  override suspend fun waitForAnimationToEnd() {
    maestro.waitForAnimationToEnd(null)
  }

  override fun close() {
    maestro.close()
    iosDevice.close()
  }
}
