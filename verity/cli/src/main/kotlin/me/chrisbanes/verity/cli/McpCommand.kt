package me.chrisbanes.verity.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import java.io.File
import kotlinx.coroutines.runBlocking
import me.chrisbanes.verity.mcp.VerityMcpServer

class McpCommand : CliktCommand(name = "mcp") {
  override fun help(context: Context): String = "Start the MCP server for interactive device control"

  private val transport by option("--transport", help = "Transport mode: stdio or http")
    .default("stdio")
  private val port by option("--port", help = "HTTP port (only for http transport)")
    .int()
    .default(8080)
  private val host by option("--host", help = "HTTP host (only for http transport)")
    .default("127.0.0.1")

  override fun run() = runBlocking {
    val parent = currentContext.parent?.command as Verity
    val contextDir = parent.contextPath?.let { File(it) }

    val server = VerityMcpServer(
      contextPath = contextDir,
      skipBundledContext = parent.noBundledContext,
    )

    when (transport) {
      "stdio" -> {
        echo("Starting Verity MCP server (stdio)...")
        server.startStdio()
      }

      "http" -> {
        echo("Starting Verity MCP server on $host:$port...")
        server.startHttp(host, port)
      }

      else -> error("Unknown transport: $transport. Use 'stdio' or 'http'.")
    }
  }
}
