package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpCommandTest {
  @Test
  fun `stdio command emits only real MCP frames on stdout`() = verifyStdio(false)

  @Test
  fun `actual backend error preserves real stdio protocol frames`() = verifyStdio(true)

  @Test
  fun `all packaged archives preserve actual backend errors and real SDK stdio frames`() {
    for (classpath in packagedClasspaths()) verifyStdio(true, classpath)
  }

  private fun packagedClasspaths(): List<String> = System.getProperty("verity.cli.packaged.jars")
    .split(File.pathSeparator)
    .map { System.getProperty("verity.cli.fixture.classes") + File.pathSeparator + it }

  private fun archiveOptions(classpath: String): List<String> = if (classpath in packagedClasspaths()) {
    listOf("-Dverity.fixture.archive=${classpath.substringAfterLast(File.pathSeparator)}")
  } else {
    emptyList()
  }

  private fun verifyStdio(injectError: Boolean, classpath: String = System.getProperty("verity.cli.test.classpath")) = runBlocking {
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-mcp-command").toFile() }
    val stderr = File(directory, "stderr.txt")
    val process = start(directory, stderr, "stdio", injectError = injectError, classpath = classpath)
    val captured = ByteArrayOutputStream()
    val client = Client(Implementation("verity-command-regression", "1"))
    val input = object : FilterInputStream(process.inputStream) {
      override fun read(): Int = super.read().also { if (it >= 0) captured.write(it) }
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
        if (it > 0) captured.write(bytes, offset, it)
      }
    }
    try {
      withTimeout(30_000) {
        client.connect(StdioClientTransport(input.asSource().buffered(), process.outputStream.asSink().buffered()))
        assertThat(client.serverVersion!!.name).isEqualTo("verity")
        assertThat(client.listTools().tools.size).isEqualTo(14)
        client.ping()
      }
    } finally {
      withContext(NonCancellable) {
        try {
          withTimeout(10_000) { client.close() }
        } finally {
          stop(process)
        }
      }
    }
    try {
      val lines = captured.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
      assertThat(lines.size >= 3).isTrue()
      for (line in lines) {
        val frame = Json.parseToJsonElement(line).jsonObject
        assertThat(frame["jsonrpc"]!!.jsonPrimitive.content).isEqualTo("2.0")
        assertThat(frame.containsKey("result") || frame.containsKey("method") || frame.containsKey("error")).isTrue()
      }
      assertThat(stderr.readText()).contains("Starting Verity MCP server (stdio)...")
      if (injectError) assertThat(stderr.readText()).contains("Process failed with exit code 3")
    } finally {
      withContext(Dispatchers.IO) { directory.deleteRecursively() }
    }
  }

  @Test
  fun `http startup keeps existing stdout diagnostic`() = verifyHttpStartup()

  @Test
  fun `all packaged archives retain HTTP stdout startup`() {
    for (classpath in packagedClasspaths()) verifyHttpStartup(classpath)
  }

  private fun verifyHttpStartup(classpath: String = System.getProperty("verity.cli.test.classpath")) = runBlocking {
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-mcp-http-command").toFile() }
    val stderr = File(directory, "stderr.txt")
    val stdout = File(directory, "stdout.txt")
    val port = withContext(Dispatchers.IO) { ServerSocket(0).use { it.localPort } }
    val process = start(directory, stderr, "http", port, stdout, classpath = classpath)
    try {
      withTimeout(30_000) {
        while (!withContext(Dispatchers.IO) { stdout.readText().contains("Starting Verity MCP server on 127.0.0.1:$port...") }) {
          check(process.isAlive)
          delay(20)
        }
      }
      assertThat(stderr.readText().contains("Starting Verity MCP server on")).isFalse()
    } finally {
      stop(process)
      withContext(Dispatchers.IO) { directory.deleteRecursively() }
    }
  }

  @Test
  fun `pinned backend errors route only stdio diagnostics to stderr`() = verifyLogging(System.getProperty("verity.cli.test.classpath"))

  @Test
  fun `all packaged archives preserve stdio HTTP and ordinary logging thresholds`() {
    for (classpath in packagedClasspaths()) verifyLogging(classpath)
  }

  private fun verifyLogging(classpath: String) = runBlocking {
    for (mode in listOf("stdio", "http", "ordinary")) {
      val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-log-routing").toFile() }
      val stdout = File(directory, "stdout.txt")
      val stderr = File(directory, "stderr.txt")
      val process = withContext(Dispatchers.IO) {
        ProcessBuilder(
          listOf(
            File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx512m",
            "-cp",
            classpath,
            "me.chrisbanes.verity.cli.McpLoggingFixture",
            mode,
          ).toMutableList().apply { addAll(2, archiveOptions(classpath)) },
        ).redirectOutput(stdout).redirectError(stderr).start()
      }
      try {
        val exited = withContext(Dispatchers.IO) { process.waitFor(15, TimeUnit.SECONDS) }
        assertThat(exited).isTrue()
        assertThat(process.exitValue()).isEqualTo(0)
        val diagnostic = if (mode == "stdio") stderr.readText() else stdout.readText()
        val other = if (mode == "stdio") stdout.readText() else stderr.readText()
        assertThat(diagnostic).contains("ERROR")
        assertThat(diagnostic).contains("Process failed with exit code 3")
        assertThat(diagnostic.contains("warning must remain below default threshold")).isFalse()
        assertThat(other.contains("Process failed with exit code 3")).isFalse()
        assertThat(stdout.readText()).contains("fixture-completed")
      } finally {
        stop(process)
        withContext(Dispatchers.IO) { directory.deleteRecursively() }
      }
    }
  }

  private suspend fun start(directory: File, stderr: File, transport: String, port: Int = 0, stdout: File? = null, injectError: Boolean = false, classpath: String = System.getProperty("verity.cli.test.classpath")): Process = withContext(Dispatchers.IO) {
    val command = mutableListOf(
      File(System.getProperty("java.home"), "bin/java").path, "-Xmx512m", "-cp",
      classpath, if (injectError) "me.chrisbanes.verity.cli.McpLoggingFixture" else "me.chrisbanes.verity.cli.VerityKt",
      "--output-path", File(directory, "output").path, "mcp", "--transport", transport,
    )
    command.addAll(2, archiveOptions(classpath))
    if (transport == "http") command += listOf("--port", port.toString(), "--host", "127.0.0.1")
    ProcessBuilder(command).directory(directory).redirectError(stderr).apply {
      if (stdout != null) redirectOutput(stdout)
    }.start()
  }

  private suspend fun stop(process: Process) = withContext(Dispatchers.IO) {
    process.outputStream.close()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      check(process.waitFor(5, TimeUnit.SECONDS))
    }
    assertThat(process.isAlive).isFalse()
  }
}

/** A fresh JVM exercises the actual pinned SLF4J backend, without initializing it in the test JVM. */
object McpLoggingFixture {
  @JvmStatic
  fun main(args: Array<String>) {
    // Packaged fixtures must load production/backend/SDK classes from the selected archive.
    System.getProperty("verity.fixture.archive")?.let { archive ->
      if (archive.endsWith(".jar")) {
        for (name in listOf("me.chrisbanes.verity.cli.McpCommand", "org.slf4j.LoggerFactory", "org.apache.logging.log4j.core.appender.ConsoleAppender", "io.modelcontextprotocol.kotlin.sdk.client.Client")) {
          check(File(Class.forName(name, false, McpLoggingFixture::class.java.classLoader).protectionDomain.codeSource.location.toURI()).canonicalFile == File(archive).canonicalFile)
        }
      }
    }
    val protocol = args.size > 1
    if (protocol || args.single() == "stdio") configureStdioLogging()
    val factory = Class.forName("org.slf4j.LoggerFactory")
    val logger = factory.getMethod("getLogger", String::class.java).invoke(null, "maestro.device.util.CommandLineUtils")
    val api = Class.forName("org.slf4j.Logger")
    api.getMethod("warn", String::class.java).invoke(logger, "warning must remain below default threshold")
    api.getMethod("error", String::class.java).invoke(logger, "Process failed with exit code 3")
    if (protocol) me.chrisbanes.verity.cli.main(args) else println("fixture-completed")
  }
}
