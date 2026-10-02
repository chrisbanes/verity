package me.chrisbanes.verity.device

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import maestro.orchestra.yaml.FlowParseException
import maestro.orchestra.yaml.MaestroFlowParser
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.FileAccessScope

/** Fixed diagnostics only: never retain parser content, causes or suppressed errors. */
class InvalidMaestroFlowResponseException : Exception("Generated flow response was invalid")

class MaestroFlowValidationInfrastructureException(val phase: MaestroFlowValidationPhase) : Exception("Generated flow validation ${phase.diagnostic} failed")

enum class MaestroFlowValidationPhase(val diagnostic: String) {
  RESPONSE("response check"),
  CREATE("temporary file creation"),
  WRITE("temporary file write"),
  READ("canonical reader"),
  CLEANUP("temporary file cleanup"),
}

/** Validates with the execution parser without connecting to or executing on a device.
 * Optional operations are filesystem/SDK fault and lifecycle seams, not alternate grammars.
 */
suspend fun validateMaestroFlow(
  yaml: String,
  createTempFile: () -> Path = { Files.createTempFile("verity-validation-", ".yaml") },
  writeFlow: (Path, String) -> Unit = { path, content ->
    Files.writeString(path, content)
    Unit
  },
  readFlow: (Path) -> Unit = {
    YamlCommandReader.readCommands(it)
    Unit
  },
  deleteFlow: (Path) -> Unit = {
    Files.deleteIfExists(it)
    Unit
  },
  beforeResponseCheck: () -> Unit = {},
  ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  var path: Path? = null
  var phase = MaestroFlowValidationPhase.RESPONSE
  var failure: Throwable? = null
  var cleanupFailed = false
  try {
    currentCoroutineContext().ensureActive()
    beforeResponseCheck()
    try {
      YAMLFactory().createParser(yaml).use { parser -> while (parser.nextToken() != null) {} }
    } catch (error: CancellationException) {
      throw error
    } catch (_: JsonParseException) {
      currentCoroutineContext().ensureActive()
      throw InvalidMaestroFlowResponseException()
    }
    currentCoroutineContext().ensureActive()
    try {
      val responsePath = Path.of("generated-flow.yaml")
      MaestroFlowParser.parseConfigOnly(responsePath, yaml, FileAccessScope.everything)
      MaestroFlowParser.checkSyntax(yaml, responsePath)
    } catch (error: CancellationException) {
      throw error
    } catch (error: FlowParseException) {
      currentCoroutineContext().ensureActive()
      if (error.title in invalidTitles || invalidTitlePrefixes.any { error.title.startsWith(it) }) {
        throw InvalidMaestroFlowResponseException()
      }
      throw MaestroFlowValidationInfrastructureException(phase)
    }
    currentCoroutineContext().ensureActive()
    withContext(ioDispatcher) {
      phase = MaestroFlowValidationPhase.CREATE
      // Own the path before dispatcher return can discard a result on cancellation.
      path = createTempFile()
      currentCoroutineContext().ensureActive()
      phase = MaestroFlowValidationPhase.WRITE
      writeFlow(path, yaml)
      currentCoroutineContext().ensureActive()
      phase = MaestroFlowValidationPhase.READ
      readFlow(path)
      currentCoroutineContext().ensureActive()
    }
  } catch (error: CancellationException) {
    failure = error
  } catch (error: InvalidMaestroFlowResponseException) {
    failure = error
  } catch (error: MaestroFlowValidationInfrastructureException) {
    failure = error
  } catch (_: Throwable) {
    failure = MaestroFlowValidationInfrastructureException(phase)
  } finally {
    withContext(NonCancellable + ioDispatcher) {
      try {
        path?.let(deleteFlow)
      } catch (_: Throwable) {
        cleanupFailed = true
      }
    }
  }
  // Caller cancellation always wins, including an SDK which erased its cancellation cause.
  if (failure is CancellationException) throw failure
  currentCoroutineContext().ensureActive()
  if (cleanupFailed) throw MaestroFlowValidationInfrastructureException(MaestroFlowValidationPhase.CLEANUP)
  failure?.let { throw it }
}

private val invalidTitles = setOf("Config Section Required", "Commands Section Required", "Config Field Required", "Invalid Command", "Missing Command Options")
private val invalidTitlePrefixes = listOf("Config Field Required: ", "Invalid Command: ", "Invalid Command Format: ", "Incorrect Command Format: ", "Unknown Property: ", "Incorrect Format: ")
