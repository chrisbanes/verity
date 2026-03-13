package me.chrisbanes.verity.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import me.chrisbanes.verity.core.model.Platform

class Verity : CliktCommand(name = "verity") {
  override fun help(context: Context): String = "LLM-powered E2E testing for mobile and TV"

  val device: String? by option("--device", help = "Device ID or IP:port (auto-discover if omitted)")
  val platform: Platform by option("--platform", help = "Target platform")
    .enum<Platform>()
    .default(Platform.ANDROID_TV)
  val apiKey: String? by option("--api-key", envvar = "ANTHROPIC_API_KEY", help = "LLM API key")
  val contextPath: String? by option(
    "--context-path",
    help = "Optional path to additional context markdown files",
  )
  val noAnimations: Boolean by option("--no-animations", help = "Disable device animations")
    .flag()

  override fun run() = Unit
}

fun main(args: Array<String>) = Verity()
  .subcommands(RunCommand(), ListCommand(), McpCommand())
  .main(args)
