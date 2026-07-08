package me.chrisbanes.verity.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.chrisbanes.verity.agent.JourneyArtifactRecorder
import me.chrisbanes.verity.agent.JourneyScreenshotArtifact
import me.chrisbanes.verity.core.result.JourneyArtifactResult
import me.chrisbanes.verity.core.result.SuiteArtifactSummary

class RunArtifactWriter(
  private val outputRoot: File,
  private val clock: Clock = Clock.systemUTC(),
) {
  private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
  }

  suspend fun createRun(suiteSlugSource: String): RunArtifactDirectory = withContext(Dispatchers.IO) {
    val timestamp = DATE_FORMAT.format(clock.instant().atZone(ZoneOffset.UTC))
    val directory = outputRoot.toPath()
      .resolve("runs")
      .resolve("$timestamp-${slugArtifactName(suiteSlugSource, "run")}")
    Files.createDirectories(directory)
    RunArtifactDirectory(directory = directory, json = json)
  }

  private companion object {
    val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
  }
}

class RunArtifactDirectory(
  val directory: Path,
  private val json: Json,
) {
  fun journey(index: Int, name: String): JourneyRunArtifactRecorder {
    val key = "${index.toString().padStart(3, '0')}-${slugArtifactName(name, "journey")}"
    return JourneyRunArtifactRecorder(directory = directory, key = key)
  }

  suspend fun writeJourneyResult(path: String, result: JourneyArtifactResult) = withContext(Dispatchers.IO) {
    val target = directory.resolve(path)
    Files.createDirectories(target.parent)
    Files.writeString(target, json.encodeToString(JourneyArtifactResult.serializer(), result))
  }

  suspend fun writeSummary(summary: SuiteArtifactSummary) = withContext(Dispatchers.IO) {
    Files.writeString(directory.resolve("summary.json"), json.encodeToString(SuiteArtifactSummary.serializer(), summary))
  }
}

class JourneyRunArtifactRecorder(
  private val directory: Path,
  private val key: String,
) : JourneyArtifactRecorder {
  override suspend fun saveGeneratedFlow(segmentIndex: Int, label: String, yaml: String): String = withContext(Dispatchers.IO) {
    val relative = "flows/$key/segment-${segmentIndex.toString().padStart(3, '0')}-$label.yaml"
    val target = directory.resolve(relative)
    Files.createDirectories(target.parent)
    Files.writeString(target, yaml)
    relative
  }

  override suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String = withContext(Dispatchers.IO) {
    val relative = "evidence/$key/segment-${segmentIndex.toString().padStart(3, '0')}-tree.txt"
    val target = directory.resolve(relative)
    Files.createDirectories(target.parent)
    Files.writeString(target, hierarchy)
    relative
  }

  override suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact = withContext(Dispatchers.IO) {
    val relative = "evidence/$key/segment-${segmentIndex.toString().padStart(3, '0')}-visual.png"
    val target = directory.resolve(relative)
    Files.createDirectories(target.parent)
    JourneyScreenshotArtifact(path = target, relativePath = relative)
  }
}

internal fun slugArtifactName(value: String, fallback: String): String {
  val slug = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
  return slug.ifEmpty { fallback }
}
