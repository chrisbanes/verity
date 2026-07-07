package me.chrisbanes.verity.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import me.chrisbanes.verity.core.journey.JourneyLoader

class ListCommand : CliktCommand(name = "list") {
  override fun help(context: Context): String = "List available journey files"

  private val path by option("--path", help = "Directory to search for journeys")

  override fun run() {
    val parent = currentContext.parent?.command as Verity
    val config = VerityConfig.loadOrDefault(File("verity/config.yaml"))
    val resolved = ResolvedProjectConfig.resolve(
      config = config,
      cli = parent.projectCliOptions().copy(journeysPath = path ?: parent.journeysPath),
    )
    val dir = resolved.journeysPath
    require(dir.isDirectory) { "Not a directory: ${dir.path}" }

    val journeys = JourneyLoader.listJourneyFiles(dir)
    if (journeys.isEmpty()) {
      echo("No journey files found in ${dir.path}")
      return
    }

    echo("Found ${journeys.size} journey(s):")
    journeys.forEach { file ->
      val journey = JourneyLoader.fromFile(file)
      echo("  ${file.name} — ${journey.name} (${journey.platform})")
    }
  }
}
