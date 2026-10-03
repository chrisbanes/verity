package me.chrisbanes.verity.device

import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import maestro.Maestro
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.yaml.YamlCommandReader
import me.chrisbanes.verity.core.model.ActionFlow
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
    val result = Orchestra(maestro = maestro).runFlow(commands)
    FlowResult(success = result.success)
  } catch (error: SyntaxError) {
    FlowResult(success = false, output = error.message)
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    FlowResult(success = false, output = error.message ?: error::class.simpleName.orEmpty())
  } finally {
    withContext(NonCancellable + Dispatchers.IO) {
      Files.deleteIfExists(flowPath)
    }
  }
}

/** Preparation completes before the runtime failure boundary or any driver effect. */
internal suspend fun executeMaestroActions(
  maestro: Maestro,
  flow: ActionFlow,
  runCommands: (suspend (List<MaestroCommand>) -> Boolean)? = null,
  compile: (ActionFlow) -> List<MaestroCommand> = MaestroActionCompiler::compile,
  createOrchestra: (Maestro) -> Orchestra = { Orchestra(maestro = it) },
): FlowResult {
  currentCoroutineContext().ensureActive()
  val commands = try {
    prepareMaestroActions(flow, compile)
  } catch (error: CancellationException) {
    currentCoroutineContext().ensureActive()
    throw error
  } catch (error: Exception) {
    currentCoroutineContext().ensureActive()
    throw error
  }
  currentCoroutineContext().ensureActive()
  val execute: suspend (List<MaestroCommand>) -> Boolean = if (runCommands != null) {
    runCommands
  } else {
    val orchestra = try {
      withContext(Dispatchers.IO) { createOrchestra(maestro) }
    } catch (error: CancellationException) {
      currentCoroutineContext().ensureActive()
      throw error
    } catch (_: Exception) {
      currentCoroutineContext().ensureActive()
      throw ActionFlowPreparationException(ActionFlowPreparationPhase.RUNNER_INITIALIZATION)
    }
    val sdkRunner: suspend (List<MaestroCommand>) -> Boolean = { selected ->
      withContext(Dispatchers.IO) { orchestra.runFlow(selected).success }
    }
    sdkRunner
  }
  currentCoroutineContext().ensureActive()
  return try {
    val success = execute(commands)
    currentCoroutineContext().ensureActive()
    FlowResult(success = success)
  } catch (error: CancellationException) {
    currentCoroutineContext().ensureActive()
    throw error
  } catch (error: Exception) {
    currentCoroutineContext().ensureActive()
    FlowResult(success = false, output = error.message ?: error::class.simpleName.orEmpty())
  }
}
