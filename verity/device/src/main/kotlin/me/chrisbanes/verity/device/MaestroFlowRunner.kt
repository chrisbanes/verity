package me.chrisbanes.verity.device

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import maestro.Maestro
import maestro.orchestra.Orchestra
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.yaml.YamlCommandReader
import me.chrisbanes.verity.core.model.FlowResult

/**
 * Executes a Maestro YAML flow via [Orchestra], handling temp file lifecycle and errors.
 *
 * Shared by [me.chrisbanes.verity.device.android.AndroidDeviceSession] and
 * [me.chrisbanes.verity.device.ios.IosDeviceSession].
 */
internal suspend fun executeMaestroFlow(maestro: Maestro, yaml: String): FlowResult {
  val flowPath = withContext(Dispatchers.IO) {
    Files.createTempFile("verity-flow-", ".yaml")
  }
  return try {
    val commands = withContext(Dispatchers.IO) {
      Files.writeString(flowPath, yaml)
      YamlCommandReader.readCommands(flowPath)
    }
    val success = Orchestra(maestro = maestro).runFlow(commands)
    FlowResult(success = success)
  } catch (error: SyntaxError) {
    FlowResult(success = false, output = error.message)
  } catch (error: kotlinx.coroutines.CancellationException) {
    throw error
  } catch (error: Exception) {
    FlowResult(success = false, output = error.message ?: error::class.simpleName.orEmpty())
  } finally {
    withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
      Files.deleteIfExists(flowPath)
    }
  }
}
