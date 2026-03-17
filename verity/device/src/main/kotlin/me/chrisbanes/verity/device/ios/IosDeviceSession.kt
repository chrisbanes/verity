package me.chrisbanes.verity.device.ios

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

  override suspend fun pressKey(keyName: String): Unit = withContext(Dispatchers.IO) {
    iosDevice.pressKey(keyName)
  }

  override suspend fun captureHierarchyTree(): HierarchyNode = withContext(Dispatchers.IO) {
    val hierarchy = iosDevice.viewHierarchy(false)
    XcTestTreeConverter.convert(hierarchy.axElement)
  }

  @Suppress("DEPRECATION")
  override suspend fun captureScreenshot(output: Path): Unit = withContext(Dispatchers.IO) {
    maestro.takeScreenshot(output.toFile(), false)
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
