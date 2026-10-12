package me.chrisbanes.verity.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.optionalValue
import me.chrisbanes.verity.core.model.Platform

private val platformNames = Platform.entries.joinToString { it.wireName }

class Verity : CliktCommand(name = "verity") {
  override fun help(context: Context): String = "LLM-powered E2E testing for mobile and TV"

  val device: String? by option("--device", help = "Device ID or IP:port (auto-discover if omitted)")
  val platform: Platform? by option(
    "--platform",
    help = "Override target platform: $platformNames (defaults to journey platform)",
  ).convert { Platform.fromWireName(it) ?: fail("'$it' is not a platform. Expected one of: $platformNames") }
  val provider: String? by option("--provider", help = "LLM provider (e.g., anthropic, openai, google, ollama)")
  val openaiAuth: String? by option("--openai-auth", help = "OpenAI authentication: api-key or chatgpt")
  val navigatorModel: String? by option("--navigator-model", help = "Override navigator model ID")
  val inspectorModel: String? by option("--inspector-model", help = "Override inspector model ID")
  val navigatorEffort: String? by option("--navigator-effort", help = "Override navigator reasoning effort")
  val inspectorEffort: String? by option("--inspector-effort", help = "Override inspector reasoning effort")
  val apiKey: String? by option("--api-key", help = "LLM API key (or set provider-specific env var)")
  val journeysPath: String? by option("--journeys-path", help = "Default journey file or directory")
  val outputPath: String? by option("--output-path", help = "Directory for generated run artifacts")
  val assertionStrategy: String? by option(
    "--assertion-strategy",
    help = "Implicit assertion strategy: infer, visible, focused, tree, or visual",
  )
  val contextPath: String? by option(
    "--context-path",
    help = "Optional path to additional context markdown files",
  )
  val requireContext: Boolean by option(
    "--require-context",
    help = "Fail when project context is not configured or contains no markdown files",
  ).flag()
  val noAnimations: Boolean? by option("--no-animations", help = "Disable device animations")
    .convert { it.toBooleanStrict() }
    // Only an attached value (`--no-animations=false`) is read, so a following subcommand isn't consumed.
    .optionalValue(true, acceptsUnattachedValue = false)
  val noBundledContext: Boolean by option(
    "--no-bundled-context",
    help = "Skip built-in Maestro and TV controls context",
  ).flag()

  override fun run() = Unit
}

fun main(args: Array<String>) = Verity()
  .subcommands(RunCommand(), ListCommand(), McpCommand())
  .main(args)

fun Verity.projectCliOptions(): ProjectCliOptions = ProjectCliOptions(
  journeysPath = journeysPath,
  contextPath = contextPath,
  outputPath = outputPath,
  platform = platform?.wireName,
  deviceId = device,
  disableAnimations = noAnimations,
  provider = provider,
  openaiAuth = openaiAuth,
  navigatorModel = navigatorModel,
  inspectorModel = inspectorModel,
  navigatorEffort = navigatorEffort,
  inspectorEffort = inspectorEffort,
  assertionStrategy = assertionStrategy,
)
