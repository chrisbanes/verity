package me.chrisbanes.verity.device.ios

import java.nio.file.Path
import maestro.Maestro
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.executeMaestroFlow

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

  override suspend fun executeFlow(yaml: String): FlowResult = executeMaestroFlow(maestro, yaml)

  override suspend fun pressKey(keyName: String) {
    iosDevice.pressKey(keyName)
  }

  override suspend fun captureHierarchyTree(): HierarchyNode {
    val hierarchy = iosDevice.viewHierarchy(false)
    return XcTestTreeConverter.convert(hierarchy.axElement)
  }

  @Suppress("DEPRECATION")
  override suspend fun captureScreenshot(output: Path) {
    maestro.takeScreenshot(output.toFile(), false)
  }

  override suspend fun shell(command: String): String {
    val process = ProcessBuilder("xcrun", "simctl", *command.split(" ").toTypedArray())
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "xcrun simctl command failed (exit $exitCode): $output" }
    return output
  }

  override suspend fun waitForAnimationToEnd() {
    maestro.waitForAnimationToEnd(null)
  }

  override fun close() {
    maestro.close()
    iosDevice.close()
  }
}
