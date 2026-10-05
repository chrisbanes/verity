package me.chrisbanes.verity.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Startup-only prepared backend; model execution is added at the next owned boundary. */
internal class CodexModelBackend private constructor(
  internal val client: CodexAppServerClient,
  internal val isolation: CodexIsolation,
  internal val workingDirectory: Path,
  internal val resources: CodexOwnedResources,
) {
  suspend fun close() {
    try {
      resources.close()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      throw CodexFailure(CodexFailureKind.CLEANUP)
    }
  }

  companion object {
    suspend fun prepare(
      executable: suspend () -> Path? = { findExecutable() },
      launch: (List<String>, Path, Map<String, String>) -> Process = ::launchProcess,
      host: String = System.getProperty("os.name"),
      environment: Map<String, String> = System.getenv(),
      startupMillis: Long = 30_000,
    ): CodexModelBackend {
      val resources = CodexOwnedResources()
      try {
        return withTimeout(startupMillis) {
          if (host != "Mac OS X") throw CodexFailure(CodexFailureKind.HOST)
          val binary = executable() ?: throw CodexFailure(CodexFailureKind.INSTALLATION)
          val childEnvironment = environment - setOf("OPENAI_API_KEY", "CODEX_API_KEY")
          val versionDirectory = resources.directory()
          val versionProcess = resources.launch(listOf(binary.toString(), "--version"), versionDirectory, childEnvironment, launch)
          val versionOutput = resources.scope.async { CodexAppServerClient.readFrame(versionProcess.inputStream) }.await()
          awaitExit(versionProcess)
          if (versionProcess.exitValue() != 0 || !supportedVersion(versionOutput)) throw CodexFailure(CodexFailureKind.VERSION)
          val schemaDirectory = resources.directory()
          val schemaWorkingDirectory = resources.directory()
          val schemaProcess = resources.launch(listOf(binary.toString(), "app-server", "generate-json-schema", "--experimental", "--out", schemaDirectory.toString()), schemaWorkingDirectory, childEnvironment, launch)
          val schemaDrain = resources.scope.launch {
            schemaProcess.inputStream.use { stream ->
              val buffer = ByteArray(4096)
              while (stream.read(buffer) != -1) { }
            }
          }
          val schemaErrorDrain = resources.scope.launch {
            schemaProcess.errorStream.use { stream ->
              val buffer = ByteArray(4096)
              while (stream.read(buffer) != -1) { }
            }
          }
          awaitExit(schemaProcess)
          schemaDrain.join()
          schemaErrorDrain.join()
          if (schemaProcess.exitValue() != 0) throw CodexFailure(CodexFailureKind.PROTOCOL)
          CodexIsolation.validateSchema(schemaDirectory)
          val bootstrapPolicy = CodexIsolation()
          val bootstrapDirectory = resources.directory()
          val bootstrap = resources.client(resources.launch(listOf(binary.toString(), "app-server", "--listen", "stdio://") + bootstrapPolicy.arguments(), bootstrapDirectory, childEnvironment, launch))
          initialize(bootstrap)
          val discoveredPolicy = bootstrapPolicy.verify(readConfig(bootstrap))
          bootstrap.stop()
          val finalDirectory = resources.directory()
          val final = resources.client(resources.launch(listOf(binary.toString(), "app-server", "--listen", "stdio://") + discoveredPolicy.arguments(), finalDirectory, childEnvironment, launch))
          initialize(final)
          discoveredPolicy.verify(readConfig(final))
          CodexModelBackend(final, discoveredPolicy, finalDirectory, resources)
        }
      } catch (e: CancellationException) {
        var cleanupFailed = false
        try {
          resources.close()
        } catch (_: Exception) {
          cleanupFailed = true
        }
        if (e is TimeoutCancellationException && !currentCoroutineContext().isActive) throw e
        if (e is TimeoutCancellationException) throw CodexFailure(if (cleanupFailed) CodexFailureKind.CLEANUP else CodexFailureKind.STARTUP_TIMEOUT)
        throw e
      } catch (e: Exception) {
        try {
          resources.close()
        } catch (_: Exception) {
          throw CodexFailure(CodexFailureKind.CLEANUP)
        }
        throw if (e is CodexFailure) e else CodexFailure(CodexFailureKind.PROTOCOL)
      }
    }

    private suspend fun initialize(client: CodexAppServerClient) {
      client.request(
        "initialize",
        buildJsonObject {
          put(
            "clientInfo",
            buildJsonObject {
              put("name", "verity")
              put("version", "1")
            },
          )
          put(
            "capabilities",
            buildJsonObject {
              put("experimentalApi", true)
              put("explicitGatewayOauth", true)
              put("requestAttestation", false)
            },
          )
        },
      )
      client.notify("initialized")
    }

    private suspend fun readConfig(client: CodexAppServerClient): JsonObject = client.request("config/read")["config"] as? JsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)

    private suspend fun awaitExit(process: Process) {
      while (process.isAlive) delay(10)
    }

    internal fun supportedVersion(value: String?): Boolean {
      val match = value?.let { Regex("codex-cli (\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(it) } ?: return false
      val numbers = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
      return numbers[0] > 0 || numbers[1] >= 159
    }

    private suspend fun findExecutable(): Path? = withContext(Dispatchers.IO) {
      System.getenv("PATH")?.split(java.io.File.pathSeparator)?.map { Path.of(it).resolve("codex") }?.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    private fun launchProcess(command: List<String>, directory: Path, environment: Map<String, String>): Process = ProcessBuilder(command).directory(directory.toFile()).apply {
      environment().clear()
      environment().putAll(environment)
    }.start()
  }
}
