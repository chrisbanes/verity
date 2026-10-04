package me.chrisbanes.verity.device

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.sink

/** Owns staging, the complete writer and atomic publication; never exposes a partial image. */
internal suspend fun captureBoundedScreenshot(
  output: Path,
  timeout: Duration,
  checkpoint: () -> Unit = {},
  onEvent: (String) -> Unit = {},
  capture: suspend (Sink, () -> Unit, () -> Long) -> Unit,
) {
  require(timeout.isPositive() && timeout.isFinite()) { "Capture timeout must be positive and finite" }
  val parent = currentCoroutineContext()
  val started = System.nanoTime()
  val target = output.toAbsolutePath()
  var staging: Path? = null
  var previous: List<ByteArray>? = null
  var published = false
  try {
    val complete = withTimeoutOrNull(timeout) {
      withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        fun checkCapture() {
          context.ensureActive()
          checkpoint()
          if (System.nanoTime() - started >= timeout.inWholeNanoseconds) throw ScreenshotCaptureTimeoutException()
        }
        checkCapture()
        Files.createDirectories(target.parent)
        checkCapture()
        staging = Files.createTempFile(target.parent, ".verity-screenshot-", ".partial")
        checkCapture()
        CheckedScreenshotSink(staging!!.sink(), ::checkCapture).use { sink ->
          capture(sink, ::checkCapture) {
            checkCapture()
            (timeout.inWholeNanoseconds - (System.nanoTime() - started)).coerceAtLeast(1)
          }
        }
        check(Files.size(staging) > 0) { "Screenshot capture produced no bytes" }
        onEvent("writer-complete")
        checkCapture()
        // A publication failure or cancellation observed after the atomic move must restore prior bytes.
        if (Files.exists(target)) {
          // Retain checked chunks until admission, so disposing a file backup cannot create
          // an unchecked cleanup stage after commit or destroy rollback data before expiry.
          val chunks = mutableListOf<ByteArray>()
          Files.newInputStream(target).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
              checkCapture()
              val count = input.read(buffer)
              checkCapture()
              if (count < 0) break
              chunks += buffer.copyOf(count)
              checkCapture()
            }
          }
          previous = chunks
        }
        checkCapture()
        Files.move(staging, target, ATOMIC_MOVE, REPLACE_EXISTING)
        published = true
        onEvent("published")
        checkCapture()
        true
      }
    }
    // Close owned staging before admitting completion; cleanup consumes this same budget.
    withContext(NonCancellable + Dispatchers.IO) {
      staging?.let(Files::deleteIfExists)
      onEvent("capture-closed")
      onEvent("owned-cleanup-complete")
    }
    parent.ensureActive()
    if (complete != true || System.nanoTime() - started >= timeout.inWholeNanoseconds) throw ScreenshotCaptureTimeoutException()
  } catch (failure: Throwable) {
    // A failed admission still owns publication rollback. Join recovery before prioritizing
    // caller cancellation; no owned filesystem cleanup remains after successful admission.
    val recoveryFailure = runCatching {
      withContext(NonCancellable + Dispatchers.IO) {
        if (published) {
          val original = previous
          if (original == null) {
            Files.deleteIfExists(target)
          } else {
            val restore = Files.createTempFile(target.parent, ".verity-screenshot-", ".restore")
            try {
              Files.newOutputStream(restore).use { output -> original.forEach(output::write) }
              Files.move(restore, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
              Files.deleteIfExists(restore)
            }
          }
        }
        staging?.let(Files::deleteIfExists)
      }
    }.exceptionOrNull()
    parent.ensureActive()
    recoveryFailure?.let(failure::addSuppressed)
    throw failure
  }
}

/** Chunk boundaries cover SDK byte-array writes as well as streaming transport responses. */
internal class CheckedScreenshotSink(private val delegate: Sink, private val checkpoint: () -> Unit) : Sink {
  private var closed = false

  override fun write(source: Buffer, byteCount: Long) {
    check(!closed) { "Screenshot sink is closed" }
    var remaining = byteCount
    while (remaining > 0) {
      checkpoint()
      val count = minOf(remaining, 8192)
      delegate.write(source, count)
      remaining -= count
      checkpoint()
    }
  }

  override fun flush() {
    check(!closed) { "Screenshot sink is closed" }
    checkpoint()
    delegate.flush()
    checkpoint()
  }

  override fun timeout(): Timeout = delegate.timeout()

  override fun close() {
    if (closed) return
    try {
      checkpoint()
    } finally {
      closed = true
      delegate.close()
    }
    checkpoint()
  }
}
