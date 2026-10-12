package me.chrisbanes.verity.cli

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant

/** Receives one opt-in diagnostic record. Implementations must never throw. */
fun interface ModelTraceSink {
  fun write(source: String, text: String)
}

/** An appended, owner-only `--model-trace` file. The first write error disables it for the rest of the run. */
class ModelTrace private constructor(
  private val channel: FileChannel,
  private val clock: Clock,
) : ModelTraceSink,
  AutoCloseable {
  private var enabled = true

  @Synchronized
  override fun write(source: String, text: String) {
    if (!enabled) return
    try {
      val buffer = ByteBuffer.wrap((formatTraceLine(Instant.now(clock), source, text) + "\n").toByteArray(Charsets.UTF_8))
      while (buffer.hasRemaining()) channel.write(buffer)
    } catch (_: Exception) {
      enabled = false
    }
  }

  @Synchronized
  override fun close() {
    enabled = false
    runCatching { channel.close() }
  }

  companion object {
    const val MAX_LINE_BYTES = 16 * 1024

    /** Refuses symlinks and non-regular files; a new file is created `0600`. */
    fun open(path: Path, clock: Clock = Clock.systemUTC()): ModelTrace {
      try {
        // Exclusive creation can only produce a regular file owned by this process.
        val created = FileChannel.open(
          path,
          setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS),
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
        return ModelTrace(created, clock)
      } catch (_: FileAlreadyExistsException) {
        // Fall through to append to the existing entry.
      }
      val before = regularFileKey(path)
      val channel = FileChannel.open(path, setOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS))
      // ponytail: Java cannot fstat a channel, so a FIFO swapped in before the open can still block it; this rejects any other replacement.
      if (regularFileKey(path) != before) {
        channel.close()
        throw IOException("Model trace was replaced while opening")
      }
      return ModelTrace(channel, clock)
    }

    private fun regularFileKey(path: Path): Any? {
      val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
      if (!attributes.isRegularFile) throw IOException("Model trace is not a regular file")
      return attributes.fileKey()
    }
  }
}

/** One physical line: `<instant> <source> <escaped text>`, capped at [ModelTrace.MAX_LINE_BYTES] UTF-8 bytes. */
internal fun formatTraceLine(instant: Instant, source: String, text: String): String {
  val escaped = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
  val line = "$instant $source $escaped"
  val bytes = line.toByteArray(Charsets.UTF_8)
  if (bytes.size <= ModelTrace.MAX_LINE_BYTES) return line
  // The marker length depends on N, so reserve room for the widest possible count.
  val budget = ModelTrace.MAX_LINE_BYTES - "…[truncated ${bytes.size} bytes]".toByteArray(Charsets.UTF_8).size
  var end = 0
  var used = 0
  while (end < line.length) {
    val next = line.offsetByCodePoints(end, 1)
    val size = line.substring(end, next).toByteArray(Charsets.UTF_8).size
    if (used + size > budget) break
    used += size
    end = next
  }
  return line.substring(0, end) + "…[truncated ${bytes.size - used} bytes]"
}
