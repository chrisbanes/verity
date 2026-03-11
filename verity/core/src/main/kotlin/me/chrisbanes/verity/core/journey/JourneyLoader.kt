package me.chrisbanes.verity.core.journey

import com.charleskorn.kaml.Yaml
import java.io.File
import me.chrisbanes.verity.core.model.Journey

object JourneyLoader {

  private val yaml = Yaml.default

  fun fromYaml(yamlText: String): Journey = yaml.decodeFromString(Journey.serializer(), yamlText)

  fun fromFile(file: File): Journey = fromYaml(file.readText())

  fun listJourneyFiles(directory: File): List<File> = directory.listFiles()
    ?.filter { it.isFile && it.name.endsWith(".journey.yaml") }
    ?.sortedBy { it.name }
    ?: emptyList()
}
