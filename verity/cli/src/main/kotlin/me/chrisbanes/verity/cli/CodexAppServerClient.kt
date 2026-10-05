package me.chrisbanes.verity.cli

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
  private val failed = java.util.concurrent.atomic.AtomicBoolean(false)

  @Volatile private var discardedOwnership: Pair<String?, String?>? = null

  fun correlateDiscarded(threadId: String?, turnId: String?) {
    discardedOwnership = threadId to turnId
  }
  fun clearDiscardedCorrelation() {
    discardedOwnership = null
  }
  fun ownsTurn(threadId: String, turnId: String): Boolean = discardedOwnership == (threadId to turnId)
  private val writeMutex = Mutex()
  private var nextId = 0L
  private val reader = scope.launch(Dispatchers.IO) {
    try {
      while (true) {
        val line = readFrame(process.inputStream) ?: break
        val envelope = Json.parseToJsonElement(line) as? JsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
        if (envelope.containsKey("method") && envelope.containsKey("id")) {
          // Refuse every request the owned reader observes, including requests preceding
          // an ACK that the consumer has not yet validated. Never dispatch a callback.
          failed.set(true)
          send(
            buildJsonObject {
              put("id", envelope.getValue("id"))
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
        val method = envelope["method"]?.jsonPrimitive?.contentOrNull
        if (method in discardedNotifications && !envelope.containsKey("id")) {
          val ownership = discardedOwnership
          if (ownership == null) continue // Startup has no model request ownership.
          val (thread, turn) = ownership
          if (thread != null && turn != null) {
            val params = envelope["params"] as? JsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
            if (params["threadId"] != JsonPrimitive(thread) || params["turnId"] != JsonPrimitive(turn)) throw CodexFailure(CodexFailureKind.PROTOCOL)
            continue
          }
          // An ACK may be queued but not yet validated. Let the sole request consumer
          // validate this informational frame after the preceding ACK publishes ownership.
        }
        if (!frames.trySend(envelope).isSuccess) throw CodexFailure(CodexFailureKind.PROTOCOL)
      }
      failed.set(true)
      frames.close(CodexFailure(CodexFailureKind.PROTOCOL))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      failed.set(true)
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

  suspend fun beginRequest(method: String, params: JsonObject = JsonObject(emptyMap())): Long {
    if (method != "turn/interrupt" && method != "thread/unsubscribe") requireHealthy()
    val id = ++nextId
    send(
      buildJsonObject {
        put("id", id)
        put("method", method)
        put("params", params)
      },
    )
    return id
  }

  suspend fun request(
    method: String,
    params: JsonObject = JsonObject(emptyMap()),
    notification: suspend (JsonObject) -> Unit = { throw CodexFailure(CodexFailureKind.PROTOCOL) },
  ): JsonObject {
    val id = beginRequest(method, params)
    while (true) {
      val frame = receive()
      if (frame.containsKey("method")) {
        notification(frame)
        continue
      }
      if (frame["id"] != JsonPrimitive(id) || frame.containsKey("error")) throw CodexFailure(CodexFailureKind.PROTOCOL)
      requireHealthy()
      return frame["result"] as? JsonObject ?: throw CodexFailure(CodexFailureKind.PROTOCOL)
    }
  }

  fun requireHealthy() {
    if (failed.get()) throw CodexFailure(CodexFailureKind.PROTOCOL)
  }

  suspend fun receive(): JsonObject {
    requireHealthy()
    val frame = frames.receive()
    requireHealthy()
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
  suspend fun directory(): Path = withContext(Dispatchers.IO) {
    Files.createTempDirectory("verity-codex-").also { directories.add(it) }
  }

  suspend fun launch(command: List<String>, directory: Path, environment: Map<String, String>, factory: (List<String>, Path, Map<String, String>) -> Process): Process = withContext(Dispatchers.IO) {
    factory(command, directory, environment).also { processes.add(it) }
  }

  fun client(process: Process): CodexAppServerClient = CodexAppServerClient(process, scope).also { clients.add(it) }

  private val closeMutex = Mutex()
  private var deadline: CodexCleanupDeadline? = null

  @Volatile private var closed = false

  @Volatile var cleanupFailed: Boolean = false
    private set

  @Synchronized
  fun beginCleanup(proposed: CodexCleanupDeadline = CodexCleanupDeadline()): CodexCleanupDeadline = deadline ?: proposed.also { deadline = it }

  suspend fun close(
    deadline: CodexCleanupDeadline = beginCleanup(),
    beforeStop: suspend () -> Unit = {},
  ) = withContext(NonCancellable) {
    val sharedDeadline = beginCleanup(deadline)
    if (closed) {
      if (cleanupFailed) throw CodexFailure(CodexFailureKind.CLEANUP)
      return@withContext
    }
    val closeCompleted = withTimeoutOrNull(sharedDeadline.remainingMillis()) {
      closeMutex.withLock {
        if (closed) {
          if (cleanupFailed) throw CodexFailure(CodexFailureKind.CLEANUP)
          return@withLock
        }
        var failed = false
        val completed = withTimeoutOrNull(sharedDeadline.remainingMillis()) {
          // A blocked RPC write cannot be bounded by cancellation alone. Reserve time to
          // terminate the exact process, unblock the write, and join its owned callback.
          val cleanup = scope.async { beforeStop() }
          val rpcCompleted = withTimeoutOrNull(min(400L, sharedDeadline.remainingMillis())) {
            try {
              cleanup.await()
              true
            } catch (e: CancellationException) {
              throw e
            } catch (_: Exception) {
              failed = true
              true
            }
          }
          if (rpcCompleted != true) {
            failed = true
            cleanup.cancel()
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
          cleanup.cancelAndJoin()
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
        if (completed != true || failed) cleanupFailed = true
        closed = true
        if (cleanupFailed) throw CodexFailure(CodexFailureKind.CLEANUP)
      }
      true
    }
    if (closeCompleted != true) {
      cleanupFailed = true
      closed = true
      throw CodexFailure(CodexFailureKind.CLEANUP)
    }
  }
}

/** A cleanup budget is created once and shared by every phase and caller. */
internal class CodexCleanupDeadline(private val nanoTime: () -> Long = System::nanoTime) {
  private val endNanos = nanoTime() + 5_000_000_000L
  fun remainingMillis(): Long = ((endNanos - nanoTime()) / 1_000_000L).coerceAtLeast(0)
}
