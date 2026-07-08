package me.chrisbanes.verity.cli

object DryRunRenderer {
  fun renderJourney(report: DryRunJourneyReport): String = buildString {
    val resolved = report.resolvedJourney
    val journey = resolved.journey
    appendLine("# Dry Run: ${journey.name}")
    appendLine()
    appendLine("File: ${resolved.file.path}")
    appendLine("App: ${journey.app}")
    appendLine("Platform: ${journey.platform}")
    report.artifactFile?.let { appendLine("Artifact: ${it.path}") }
    appendLine()
    appendLine("## Launch Flow")
    appendYaml(report.launchYaml)
    report.segments.forEach { segment ->
      appendLine()
      appendLine("## Segment ${segment.index}")
      segment.actions?.let { actions ->
        appendLine()
        appendLine("Actions:")
        actions.instructions.forEach { appendLine("- $it") }
        appendLine("Kind: ${actions.kind}")
        if (actions.interactions.isNotEmpty()) {
          appendLine("Interactions:")
          actions.interactions.forEach { appendLine("- $it") }
        }
        actions.yaml?.let {
          appendLine("Generated YAML:")
          appendYaml(it)
        }
      }
      segment.loop?.let { loop ->
        appendLine()
        appendLine("Loop: ${loop.action} until ${loop.until}, max ${loop.max}")
        appendLine("Kind: ${loop.kind}")
        loop.interaction?.let { appendLine("Interaction: $it") }
        loop.yaml?.let {
          appendLine("Generated Loop YAML:")
          appendYaml(it)
        }
      }
      segment.assertion?.let { assertion ->
        appendLine()
        appendLine("Assertion: [${assertion.mode}] ${assertion.description}")
      }
    }
  }.trimEnd()

  fun renderSuite(report: DryRunSuiteReport): String = report.journeys
    .joinToString(separator = "\n\n---\n\n") { renderJourney(it) }

  private fun StringBuilder.appendYaml(yaml: String) {
    val fence = codeFenceFor(yaml)
    appendLine("${fence}yaml")
    appendLine(yaml.trimEnd())
    appendLine(fence)
  }

  private fun codeFenceFor(yaml: String): String {
    val longestRun = "`+".toRegex().findAll(yaml).maxOfOrNull { it.value.length } ?: 0
    return "`".repeat(maxOf(3, longestRun + 1))
  }
}
