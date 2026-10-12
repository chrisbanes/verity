package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.endsWith
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.startsWith
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ModelTraceTest {
  private val dir = createTempDirectory("verity-model-trace")
  private val clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC)

  @AfterTest
  fun cleanup() {
    dir.toFile().deleteRecursively()
  }

  private fun lines(path: Path) = Files.readAllLines(path)

  @Test
  fun `new trace file is owner only`() {
    val path = dir.resolve("trace.log")
    ModelTrace.open(path, clock).use { it.write("request", "hello") }

    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(path))).isEqualTo("rw-------")
    assertThat(lines(path)).isEqualTo(listOf("2026-10-12T00:00:00Z request hello"))
  }

  @Test
  fun `existing trace file is appended`() {
    val path = Files.writeString(dir.resolve("trace.log"), "existing\n")
    ModelTrace.open(path, clock).use { it.write("codex.sent", "{}") }

    assertThat(lines(path)).isEqualTo(listOf("existing", "2026-10-12T00:00:00Z codex.sent {}"))
  }

  @Test
  fun `symlink directory and missing parent are refused`() {
    val target = Files.writeString(dir.resolve("target.log"), "kept\n")
    val link = Files.createSymbolicLink(dir.resolve("link.log"), target)

    assertFailsWith<IOException> { ModelTrace.open(link, clock) }
    assertFailsWith<IOException> { ModelTrace.open(Files.createDirectory(dir.resolve("directory")), clock) }
    assertFailsWith<IOException> { ModelTrace.open(dir.resolve("missing/trace.log"), clock) }
    assertThat(Files.readString(target)).isEqualTo("kept\n")
  }

  @Test
  fun `multi-line text stays on one escaped line`() {
    val path = dir.resolve("trace.log")
    ModelTrace.open(path, clock).use { it.write("request", "a\nb\r\\c") }

    assertThat(lines(path)).isEqualTo(listOf("2026-10-12T00:00:00Z request a\\nb\\r\\\\c"))
  }

  @Test
  fun `long lines are truncated with the dropped byte count`() {
    val instant = Instant.parse("2026-10-12T00:00:00Z")
    val full = "$instant request ${"x".repeat(20_000)}"
    val line = formatTraceLine(instant, "request", "x".repeat(20_000))
    val bytes = line.toByteArray(Charsets.UTF_8).size
    val kept = line.substringBefore("…[truncated")

    assertThat(bytes).isLessThanOrEqualTo(ModelTrace.MAX_LINE_BYTES)
    assertThat(kept).isEqualTo(full.take(kept.length))
    assertThat(line).endsWith("…[truncated ${full.length - kept.length} bytes]")
  }

  @Test
  fun `truncation never splits a code point`() {
    val instant = Instant.parse("2026-10-12T00:00:00Z")
    val line = formatTraceLine(instant, "request", "😀".repeat(10_000))
    val kept = line.substringBefore("…[truncated")

    assertThat(line.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(ModelTrace.MAX_LINE_BYTES)
    assertThat(kept.last().isHighSurrogate()).isEqualTo(false)
    assertThat(kept).startsWith("$instant request 😀")
  }

  @Test
  fun `writes after close are ignored without throwing`() {
    val path = dir.resolve("trace.log")
    val trace = ModelTrace.open(path, clock)
    trace.write("request", "before")
    trace.close()
    trace.write("request", "after")

    assertThat(lines(path)).hasSize(1)
  }
}
