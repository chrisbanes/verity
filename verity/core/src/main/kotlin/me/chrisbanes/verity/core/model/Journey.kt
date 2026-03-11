package me.chrisbanes.verity.core.model

import kotlinx.serialization.Serializable
import me.chrisbanes.verity.core.parser.JourneyStepSerializer

@Serializable
data class Journey(
  val name: String,
  val app: String,
  val platform: Platform,
  val steps: List<
    @Serializable(with = JourneyStepSerializer::class)
    JourneyStep,
    >,
)
