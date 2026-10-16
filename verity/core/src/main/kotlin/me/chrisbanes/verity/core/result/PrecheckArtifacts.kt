package me.chrisbanes.verity.core.result

import kotlinx.serialization.Serializable

/** What a pre-check verdict relied on; recorded on `loop` and `wait` artifacts when the pre-check decided the condition. */
@Serializable
data class PrecheckArtifact(
  val probability: Double,
  val model: String,
  val host: String,
)

/** Per-run pre-check outcome counts for the run summary. */
@Serializable
data class PrecheckSummaryArtifact(
  val accepted: Int,
  val uncertainFallbacks: Int,
  val errorFallbacks: Int,
)
