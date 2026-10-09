package me.chrisbanes.verity.device.ios

import java.nio.file.Path
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import maestro.Maestro
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.executeMaestroActions
import me.chrisbanes.verity.device.executeMaestroFlow

/**
 * iOS device session backed by Maestro's XCTest HTTP client.
 *
 * The [maestro] instance wraps an [maestro.drivers.IOSDriver] which communicates
 * with the on-device XCTest runner over HTTP (port 22087).
 */
class IosDeviceSession(
  internal val maestro: Maestro,
  private val iosDevice: device.IOSDevice,
  private val onCommandStart: ((Int) -> Unit)? = null,
  private val boundedCapture: (suspend (Duration) -> HierarchyNode)? = null,
  private val boundedScreenshot: (suspend (Path, Duration) -> Unit)? = null,
) : DeviceSession {

  override val platform: Platform = Platform.IOS

  override suspend fun executeFlow(yaml: String): FlowResult = executeMaestroFlow(maestro, yaml, onCommandStart)

  override suspend fun executeActions(flow: ActionFlow): FlowResult {
    val result = executeMaestroActions(maestro, flow, onCommandStart = onCommandStart)
    // ponytail: settles only at the end of the flow; actions after LaunchApp in the same flow still race the launch.
    if (!result.success || flow.actions.none { it is Interaction.LaunchApp }) return result
    // Sessions without a bounded endpoint (test doubles) cannot poll safely, so they skip settling.
    boundedCapture?.let { awaitIosLaunchSettled(flow.appId, it) }
    return result
  }

  override suspend fun pressKey(keyName: String): Unit = withContext(Dispatchers.IO) {
    iosDevice.pressKey(keyName)
  }

  override suspend fun captureHierarchyTree(): HierarchyNode = withContext(Dispatchers.IO) {
    val hierarchy = iosDevice.viewHierarchy(false)
    XcTestTreeConverter.convert(hierarchy.axElement)
  }

  override suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode {
    require(timeout.isPositive() && timeout.isFinite()) { "Capture timeout must be positive and finite" }
    return checkNotNull(boundedCapture) { "This iOS session has no bounded hierarchy endpoint" }(timeout)
  }

  @Suppress("DEPRECATION")
  override suspend fun captureScreenshot(output: Path): Unit = withContext(Dispatchers.IO) {
    maestro.takeScreenshot(output.toFile(), false)
  }

  override suspend fun captureScreenshot(output: Path, timeout: Duration) {
    require(timeout.isPositive() && timeout.isFinite()) { "Capture timeout must be positive and finite" }
    return checkNotNull(boundedScreenshot) { "This iOS session has no bounded screenshot endpoint" }(output, timeout)
  }

  override suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(listOf("xcrun", "simctl") + parseCommandArgs(command))
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "xcrun simctl command failed (exit $exitCode): $output" }
    output
  }

  override suspend fun waitForAnimationToEnd(): Unit = withContext(Dispatchers.IO) {
    maestro.waitForAnimationToEnd(null)
  }

  override fun close() {
    maestro.close()
    iosDevice.close()
  }
}

internal fun parseCommandArgs(command: String): List<String> {
  val args = mutableListOf<String>()
  val current = StringBuilder()
  var quote: Char? = null
  var escaping = false

  for (ch in command.trim()) {
    if (escaping) {
      current.append(ch)
      escaping = false
      continue
    }

    when {
      ch == '\\' -> escaping = true

      quote != null && ch == quote -> quote = null

      quote != null -> current.append(ch)

      ch == '"' || ch == '\'' -> quote = ch

      ch.isWhitespace() -> {
        if (current.isNotEmpty()) {
          args += current.toString()
          current.clear()
        }
      }

      else -> current.append(ch)
    }
  }

  if (quote != null) error("Unterminated quote in command: $command")
  if (escaping) error("Trailing escape in command: $command")
  if (current.isNotEmpty()) args += current.toString()
  if (args.isEmpty()) error("Command cannot be blank")
  return args
}
