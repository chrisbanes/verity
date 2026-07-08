package me.chrisbanes.verity.cli

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    val runsDirectory = outputRoot.toPath().resolve("runs")
    Files.createDirectories(runsDirectory)
    val baseName = "$timestamp-${slugArtifactName(suiteSlugSource, "run")}"
    val directory = createUniqueDirectory(runsDirectory, baseName)
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
    val target = resolveArtifactPath(directory, path)
    Files.createDirectories(target.parent)
    writeJsonAtomically(target, json.encodeToString(JourneyArtifactResult.serializer(), result))
  }

  suspend fun writeSummary(summary: SuiteArtifactSummary) = withContext(Dispatchers.IO) {
    writeJsonAtomically(resolveArtifactPath(directory, "summary.json"), json.encodeToString(SuiteArtifactSummary.serializer(), summary))
  }
}

class JourneyRunArtifactRecorder(
  private val directory: Path,
  private val key: String,
) : JourneyArtifactRecorder {
  override suspend fun saveGeneratedFlow(segmentIndex: Int, label: String, yaml: String): String = withContext(Dispatchers.IO) {
    val relative = "flows/$key/segment-${segmentIndex.toString().padStart(3, '0')}-${slugArtifactName(label, "flow")}.yaml"
    val target = resolveArtifactPath(directory, relative)
    Files.createDirectories(target.parent)
    Files.writeString(target, yaml)
    relative
  }

  override suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String = withContext(Dispatchers.IO) {
    val relative = "evidence/$key/segment-${segmentIndex.toString().padStart(3, '0')}-tree.txt"
    val target = resolveArtifactPath(directory, relative)
    Files.createDirectories(target.parent)
    Files.writeString(target, hierarchy)
    relative
  }

  override suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact = withContext(Dispatchers.IO) {
    val relative = "evidence/$key/segment-${segmentIndex.toString().padStart(3, '0')}-visual.png"
    val target = resolveArtifactPath(directory, relative)
    Files.createDirectories(target.parent)
    JourneyScreenshotArtifact(path = target, relativePath = relative)
  }
}

internal fun slugArtifactName(value: String, fallback: String): String {
  val slug = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
  return slug.ifEmpty { fallback }
}

private fun createUniqueDirectory(parent: Path, baseName: String): Path {
  var attempt = 1
  while (true) {
    val name = if (attempt == 1) baseName else "$baseName-$attempt"
    val candidate = parent.resolve(name)
    try {
      return Files.createDirectory(candidate)
    } catch (_: FileAlreadyExistsException) {
      attempt += 1
    }
  }
}

private fun resolveArtifactPath(directory: Path, relative: String): Path {
  val relativePath = Path.of(relative)
  require(!relativePath.isAbsolute) { "Artifact path must be relative: $relative" }

  val root = directory.normalize()
  val target = root.resolve(relativePath).normalize()
  require(target.startsWith(root)) { "Artifact path escapes run directory: $relative" }
  return target
}

private fun writeJsonAtomically(target: Path, json: String) {
  val temp = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
  try {
    Files.writeString(temp, json)
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }
  } finally {
    Files.deleteIfExists(temp)
  }
}
