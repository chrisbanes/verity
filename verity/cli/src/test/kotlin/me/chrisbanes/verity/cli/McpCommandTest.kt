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
  fun `stdio command emits only real MCP frames on stdout`() = runBlocking {
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-mcp-command").toFile() }
    val stderr = File(directory, "stderr.txt")
    val process = start(directory, stderr, "stdio")
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
    } finally {
      withContext(Dispatchers.IO) { directory.deleteRecursively() }
    }
  }

  @Test
  fun `http startup keeps existing stdout diagnostic`() = runBlocking {
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-mcp-http-command").toFile() }
    val stderr = File(directory, "stderr.txt")
    val stdout = File(directory, "stdout.txt")
    val port = withContext(Dispatchers.IO) { ServerSocket(0).use { it.localPort } }
    val process = start(directory, stderr, "http", port, stdout)
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

  private suspend fun start(directory: File, stderr: File, transport: String, port: Int = 0, stdout: File? = null): Process = withContext(Dispatchers.IO) {
    val command = mutableListOf(
      File(System.getProperty("java.home"), "bin/java").path, "-Xmx512m", "-cp",
      System.getProperty("verity.cli.test.classpath"), "me.chrisbanes.verity.cli.VerityKt",
      "--output-path", File(directory, "output").path, "mcp", "--transport", transport,
    )
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
