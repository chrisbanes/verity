package me.chrisbanes.verity.agent

import java.nio.file.Path

interface JourneyArtifactRecorder {
  suspend fun saveGeneratedFlow(segmentIndex: Int, label: String, yaml: String): String? = null
  suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String? = null
  suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact? = null
}

data class JourneyScreenshotArtifact(
  val path: Path,
  val relativePath: String,
)

object NoOpJourneyArtifactRecorder : JourneyArtifactRecorder
