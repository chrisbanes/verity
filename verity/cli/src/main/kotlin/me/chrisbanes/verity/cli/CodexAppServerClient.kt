package me.chrisbanes.verity.cli

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One exact owned process and bounded NDJSON reader. Stderr is drained and discarded. */
internal class CodexAppServerClient internal constructor(
  val process: Process,
  private val scope: CoroutineScope,
) {
  private val frames = Channel<JsonObject>(64)
  private val writeMutex = Mutex()
  private var nextId = 0L
  private val reader = scope.launch(Dispatchers.IO) {
    try {
      while (true) {
        val line = readFrame(process.inputStream) ?: break
        val envelope = Json.parseToJsonElement(line) as? JsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        val method = envelope["method"]?.jsonPrimitive?.contentOrNull
        if (method in discardedNotifications && !envelope.containsKey("id")) continue
        if (!frames.trySend(envelope).isSuccess) throw CodexFailure(CodexFailureKind.PROTOCOL)
      }
      frames.close(CodexFailure(CodexFailureKind.PROTOCOL))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      frames.close(CodexFailure(CodexFailureKind.PROTOCOL))
    }
  }
  private val stderrReader = scope.launch(Dispatchers.IO) {
    try {
      val buffer = ByteArray(4096)
      while (process.errorStream.read(buffer) != -1) { /* Never retain stderr. */ }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) { /* Owned stream was closed. */ }
  }

  suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
    val id = ++nextId
    send(
      buildJsonObject {
        put("id", id)
        put("method", method)
        put("params", params)
      },
    )
    while (true) {
      val frame = receive()
      if (frame.containsKey("method")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      if (frame["id"] != JsonPrimitive(id) || frame.containsKey("error")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      return frame["result"] as? JsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
    }
  }

  suspend fun receive(): JsonObject {
    val frame = frames.receive()
    if (frame.containsKey("method") && frame.containsKey("id")) {
      send(
        buildJsonObject {
          put("id", frame.getValue("id"))
          put(
            "error",
            buildJsonObject {
              put("code", -32601)
              put("message", "Requests are disabled")
            },
          )
        },
      )
      throw CodexFailure(CodexFailureKind.PROTOCOL)
    }
    return frame
  }

  suspend fun notify(method: String, params: JsonObject = JsonObject(emptyMap())) = send(
    buildJsonObject {
      put("method", method)
      put("params", params)
    },
  )

  suspend fun send(frame: JsonObject) = writeMutex.withLock {
    val bytes = (frame.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
    if (bytes.size > MAX_FRAME_BYTES) throw CodexFailure(CodexFailureKind.PROTOCOL)
    withContext(Dispatchers.IO) {
      process.outputStream.write(bytes)
      process.outputStream.flush()
    }
  }

  internal suspend fun stop() {
    // A pipe close can wait for an outstanding writer. Do it concurrently so exact-handle
    // termination remains available to unblock both readers and writers.
    val stdinCloser = scope.launch(Dispatchers.IO) { runCatching { process.outputStream.close() } }
    withContext(Dispatchers.IO) {
      if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        process.destroy()
        if (!process.waitFor(100, TimeUnit.MILLISECONDS)) process.destroyForcibly()
      }
      if (!process.waitFor(500, TimeUnit.MILLISECONDS)) throw CodexFailure(CodexFailureKind.CLEANUP)
      runCatching { process.inputStream.close() }
      runCatching { process.errorStream.close() }
    }
    stdinCloser.cancelAndJoin()
    reader.cancelAndJoin()
    stderrReader.cancelAndJoin()
    frames.cancel()
  }

  companion object {
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    private val discardedNotifications = setOf("item/agentMessage/delta", "item/reasoning/textDelta", "item/reasoning/summaryTextDelta", "thread/tokenUsage/updated")

    internal fun readFrame(input: InputStream): String? {
      val bytes = ByteArrayOutputStream()
      while (true) {
        val value = input.read()
        if (value == -1) {
          if (bytes.size() == 0) return null
          throw CodexFailure(CodexFailureKind.PROTOCOL)
        }
        if (value == 10) break
        if (bytes.size() >= MAX_FRAME_BYTES) throw CodexFailure(CodexFailureKind.PROTOCOL)
        bytes.write(value)
      }
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
  }
}

/** Owns only newly launched exact handles and newly created directories. */
internal class CodexOwnedResources {
  private val job = SupervisorJob()
  val scope = CoroutineScope(job + Dispatchers.IO)
  private val processes = mutableListOf<Process>()
  private val clients = mutableListOf<CodexAppServerClient>()
  private val directories = mutableListOf<Path>()
  var ownedThreadId: String? = null
  var ownedTurnId: String? = null

  suspend fun directory(): Path = withContext(Dispatchers.IO) {
    Files.createTempDirectory("verity-codex-").also { directories.add(it) }
  }

  suspend fun launch(command: List<String>, directory: Path, environment: Map<String, String>, factory: (List<String>, Path, Map<String, String>) -> Process): Process = withContext(Dispatchers.IO) {
    factory(command, directory, environment).also { processes.add(it) }
  }

  fun client(process: Process): CodexAppServerClient = CodexAppServerClient(process, scope).also { clients.add(it) }

  var cleanupFailed: Boolean = false
    private set

  suspend fun close() = withContext(NonCancellable) {
    var failed = false
    val completed = withTimeoutOrNull(5_000) {
      // T3 records these IDs only after acquiring the corresponding owned resources.
      val client = clients.lastOrNull { it.process.isAlive }
      if (client != null) {
        ownedTurnId?.let { turn ->
          ownedThreadId?.let { thread ->
            val interrupted = withTimeoutOrNull(200) {
              try {
                client.request(
                  "turn/interrupt",
                  buildJsonObject {
                    put("threadId", thread)
                    put("turnId", turn)
                  },
                )
              } catch (e: CancellationException) {
                throw e
              } catch (_: Exception) {
                failed = true
              }
            }
            if (interrupted == null) failed = true
          }
        }
        ownedThreadId?.let { thread ->
          val unsubscribed = withTimeoutOrNull(200) {
            try {
              client.request("thread/unsubscribe", buildJsonObject { put("threadId", thread) })
            } catch (e: CancellationException) {
              throw e
            } catch (_: Exception) {
              failed = true
            }
          }
          if (unsubscribed == null) failed = true
        }
      }
      clients.asReversed().forEach { client ->
        try {
          client.stop()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          failed = true
        }
      }
      withContext(Dispatchers.IO) {
        processes.asReversed().forEach { process ->
          try {
            if (process.isAlive) {
              process.destroy()
              if (!process.waitFor(100, TimeUnit.MILLISECONDS)) process.destroyForcibly()
              if (!process.waitFor(500, TimeUnit.MILLISECONDS)) failed = true
            }
            if (!process.isAlive) {
              runCatching { process.outputStream.close() }
              runCatching { process.inputStream.close() }
              runCatching { process.errorStream.close() }
            }
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            failed = true
          }
        }
      }
      job.cancelAndJoin()
      withContext(Dispatchers.IO) {
        val cleanupContext = currentCoroutineContext()
        if (processes.any { it.isAlive }) {
          failed = true
        } else {
          directories.asReversed().forEach { path ->
            try {
              if (Files.exists(path)) {
                Files.walk(path).use { paths ->
                  paths.sorted(Comparator.reverseOrder()).forEach {
                    cleanupContext.ensureActive()
                    Files.delete(it)
                  }
                }
              }
            } catch (e: CancellationException) {
              throw e
            } catch (_: Exception) {
              failed = true
            }
          }
          if (directories.any { Files.exists(it) }) failed = true
        }
      }
      true
    }
    cleanupFailed = cleanupFailed || completed != true || failed
    if (cleanupFailed) throw CodexFailure(CodexFailureKind.CLEANUP)
  }
}
