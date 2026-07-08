package me.chrisbanes.verity.cli

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DryRunArtifactWriter {
  suspend fun write(
    outputPath: File,
    suiteReport: DryRunSuiteReport,
  ): DryRunSuiteReport = withContext(Dispatchers.IO) {
    val directory = File(outputPath, "dry-run")
    if (!directory.mkdirs() && !directory.isDirectory) {
      throw IllegalStateException("Unable to create dry run artifact directory: ${directory.path}")
    }
    val usedNames = mutableSetOf<String>()

    DryRunSuiteReport(
      journeys =
      suiteReport.journeys.map { report ->
        val file = File(directory, uniqueArtifactName(artifactName(report.resolvedJourney.file), usedNames))
        val reportWithArtifact = report.copy(artifactFile = file)
        file.writeText(DryRunRenderer.renderJourney(reportWithArtifact))
        reportWithArtifact
      },
    )
  }

  private fun artifactName(file: File): String {
    val name = file.name
    return when {
      name.endsWith(".journey.yaml") -> name.removeSuffix(".journey.yaml") + ".md"
      name.endsWith(".yaml") -> name.removeSuffix(".yaml") + ".md"
      else -> file.nameWithoutExtension + ".md"
    }
  }

  private fun uniqueArtifactName(baseName: String, usedNames: MutableSet<String>): String {
    if (usedNames.add(baseName)) return baseName

    val extension = baseName.substringAfterLast('.', missingDelimiterValue = "")
    val stem = if (extension.isEmpty()) baseName else baseName.removeSuffix(".$extension")
    var index = 2
    while (true) {
      val candidate = if (extension.isEmpty()) "$stem-$index" else "$stem-$index.$extension"
      if (usedNames.add(candidate)) return candidate
      index++
    }
  }
}
