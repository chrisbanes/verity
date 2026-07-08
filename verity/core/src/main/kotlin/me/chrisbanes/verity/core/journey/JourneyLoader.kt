package me.chrisbanes.verity.core.journey

import com.charleskorn.kaml.Yaml
import java.io.File
import kotlinx.serialization.Serializable
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.parser.JourneyStepParser

object JourneyLoader {

  private val yaml = Yaml.default

  fun fromYaml(
    yamlText: String,
    assertionStrategy: AssertionStrategy = AssertionStrategy.INFER,
  ): Journey {
    if (assertionStrategy == AssertionStrategy.INFER) {
      return yaml.decodeFromString(Journey.serializer(), yamlText)
    }
    val raw = yaml.decodeFromString(RawJourney.serializer(), yamlText)
    return Journey(
      name = raw.name,
      app = raw.app,
      platform = raw.platform,
      steps = raw.steps.map { JourneyStepParser.parse(it, assertionStrategy) },
    )
  }

  fun fromFile(
    file: File,
    assertionStrategy: AssertionStrategy = AssertionStrategy.INFER,
  ): Journey = fromYaml(file.readText(), assertionStrategy)

  fun listJourneyFiles(directory: File): List<File> = directory.listFiles()
    ?.filter { it.isFile && it.name.endsWith(".journey.yaml") }
    ?.sortedBy { it.name }
    ?: emptyList()
}

@Serializable
private data class RawJourney(
  val name: String,
  val app: String,
  val platform: Platform,
  val steps: List<String>,
)
