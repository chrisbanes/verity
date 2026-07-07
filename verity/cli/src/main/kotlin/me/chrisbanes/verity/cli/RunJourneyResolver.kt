package me.chrisbanes.verity.cli

import java.io.File
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.Platform

fun resolveRunJourneyFile(
  cliJourneyPath: String?,
  configuredJourneysPath: File?,
): File {
  cliJourneyPath?.let { return File(it) }

  requireNotNull(configuredJourneysPath) {
    "Journey path required. Use: verity run <path.journey.yaml> or configure paths.journeys."
  }

  require(configuredJourneysPath.isFile || configuredJourneysPath.isDirectory) {
    "Journey path is not a file or directory: $configuredJourneysPath"
  }

  return configuredJourneysPath
}

fun applyResolvedPlatform(
  journey: Journey,
  platform: Platform?,
): Journey = platform?.let { journey.copy(platform = it) } ?: journey
