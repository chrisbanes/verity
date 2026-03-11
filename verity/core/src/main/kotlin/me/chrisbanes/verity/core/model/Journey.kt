package me.chrisbanes.verity.core.model

import me.chrisbanes.verity.core.parser.JourneyStepSerializer
import kotlinx.serialization.Serializable

@Serializable
data class Journey(
    val name: String,
    val app: String,
    val platform: Platform,
    val steps: List<@Serializable(with = JourneyStepSerializer::class) JourneyStep>,
)
