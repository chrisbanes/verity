package me.chrisbanes.verity.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import me.chrisbanes.verity.core.model.Platform

class Verity : CliktCommand(name = "verity") {
  override fun help(context: Context): String = "LLM-powered E2E testing for mobile and TV"

  val device: String? by option("--device", help = "Device ID or IP:port (auto-discover if omitted)")
  val platform: Platform? by option("--platform", help = "Override target platform (defaults to journey platform)")
    .enum<Platform>()
  val provider: String? by option("--provider", help = "LLM provider (e.g., anthropic, openai, google, ollama)")
  val navigatorModel: String? by option("--navigator-model", help = "Override navigator model ID")
  val inspectorModel: String? by option("--inspector-model", help = "Override inspector model ID")
  val apiKey: String? by option("--api-key", help = "LLM API key (or set provider-specific env var)")
  val contextPath: String? by option(
    "--context-path",
    help = "Optional path to additional context markdown files",
  )
  val noAnimations: Boolean by option("--no-animations", help = "Disable device animations")
    .flag()
  val noBundledContext: Boolean by option(
    "--no-bundled-context",
    help = "Skip built-in Maestro and TV controls context",
  ).flag()

  override fun run() = Unit
}

fun main(args: Array<String>) = Verity()
  .subcommands(RunCommand(), ListCommand(), McpCommand())
  .main(args)
