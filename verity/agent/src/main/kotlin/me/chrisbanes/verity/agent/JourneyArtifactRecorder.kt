package me.chrisbanes.verity.agent

import java.nio.file.Path

interface JourneyArtifactRecorder {
  /** Supply distinct evidence paths so an incomplete later poll cannot overwrite an earlier check. */
  fun forWaitCheck(checkIndex: Int): JourneyArtifactRecorder = this

  suspend fun saveGeneratedFlow(segmentIndex: Int, label: String, yaml: String): String? = null
  suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String? = null
  suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact? = null
}

data class JourneyScreenshotArtifact(
  val path: Path,
  val relativePath: String,
)

object NoOpJourneyArtifactRecorder : JourneyArtifactRecorder
