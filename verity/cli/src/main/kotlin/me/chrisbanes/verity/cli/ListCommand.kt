package me.chrisbanes.verity.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import me.chrisbanes.verity.core.journey.JourneyLoader

class ListCommand : CliktCommand(name = "list") {
  override fun help(context: Context): String = "List available journey files"

  private val path by option("--path", help = "Directory to search for journeys")
    .default(".")

  override fun run() {
    val dir = File(path)
    require(dir.isDirectory) { "Not a directory: $path" }

    val journeys = JourneyLoader.listJourneyFiles(dir)
    if (journeys.isEmpty()) {
      echo("No journey files found in $path")
      return
    }

    echo("Found ${journeys.size} journey(s):")
    journeys.forEach { file ->
      val journey = JourneyLoader.fromFile(file)
      echo("  ${file.name} — ${journey.name} (${journey.platform})")
    }
  }
}
