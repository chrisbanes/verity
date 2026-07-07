package me.chrisbanes.verity.cli

import java.io.File
import me.chrisbanes.verity.core.journey.JourneyLoader

fun resolveRunJourneyFile(
  cliJourneyPath: String?,
  configuredJourneysPath: File,
): File {
  cliJourneyPath?.let { return File(it) }

  if (configuredJourneysPath.isFile) return configuredJourneysPath

  require(configuredJourneysPath.isDirectory) {
    "Journey path is not a file or directory: $configuredJourneysPath"
  }

  val journeyFiles = JourneyLoader.listJourneyFiles(configuredJourneysPath)
  return when (journeyFiles.size) {
    1 -> journeyFiles.single()
    0 -> throw IllegalArgumentException(
      "No journey files found in ${configuredJourneysPath.path}. Provide a journey path.",
    )

    else -> throw IllegalArgumentException(
      "Multiple journey files found in ${configuredJourneysPath.path}. Provide a journey path.",
    )
  }
}
