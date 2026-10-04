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
  var previous: Path? = null
  var published = false
  var committed = false
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
          previous = Files.createTempFile(target.parent, ".verity-screenshot-", ".previous")
          Files.newInputStream(target).use { input ->
            Files.newOutputStream(previous!!).use { destination ->
              val buffer = ByteArray(8192)
              while (true) {
                checkCapture()
                val count = input.read(buffer)
                checkCapture()
                if (count < 0) break
                destination.write(buffer, 0, count)
                checkCapture()
              }
            }
          }
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
    }
    parent.ensureActive()
    if (complete != true || System.nanoTime() - started >= timeout.inWholeNanoseconds) throw ScreenshotCaptureTimeoutException()
    committed = true
  } catch (failure: Throwable) {
    parent.ensureActive()
    throw failure
  } finally {
    withContext(NonCancellable + Dispatchers.IO) {
      if (published && !committed) {
        if (previous != null) Files.move(previous, target, ATOMIC_MOVE, REPLACE_EXISTING) else Files.deleteIfExists(target)
      }
      staging?.let(Files::deleteIfExists)
      previous?.let(Files::deleteIfExists)
    }
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
