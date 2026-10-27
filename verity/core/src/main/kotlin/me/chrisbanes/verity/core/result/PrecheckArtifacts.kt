package me.chrisbanes.verity.core.result

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How the inspector answered a pre-check pass it was asked to confirm. */
@Serializable
enum class PrecheckOutcome {
  @SerialName("confirmed")
  CONFIRMED,

  @SerialName("overruled")
  OVERRULED,
}

/**
 * What a pre-check verdict relied on; recorded on `loop` and `wait` artifacts when a pre-check answered the condition.
 * [outcome] is set only when the inspector then confirmed or overruled a pass, and so decided the check.
 */
@Serializable
data class PrecheckArtifact(
  val probability: Double,
  val model: String,
  val host: String,
  val outcome: PrecheckOutcome? = null,
)

/** Per-run pre-check outcome counts for the run summary. */
@Serializable
data class PrecheckSummaryArtifact(
  val accepted: Int,
  val uncertainFallbacks: Int,
  val errorFallbacks: Int,
  /** Passes the inspector confirmed. */
  val confirmed: Int = 0,
  /** Passes the inspector overruled. */
  val overruled: Int = 0,
)
