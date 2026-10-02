package me.chrisbanes.verity.agent

import java.nio.file.Path

/** Per-call references only. Producing and bounding journey memory belongs to the caller. */
data class InspectionContext(
  val referenceText: String = "",
  val referenceScreenshots: List<Path> = emptyList(),
)
