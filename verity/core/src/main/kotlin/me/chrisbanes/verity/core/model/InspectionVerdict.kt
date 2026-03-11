package me.chrisbanes.verity.core.model

import kotlinx.serialization.Serializable

@Serializable
data class InspectionVerdict(
    val passed: Boolean,
    val reasoning: String,
)
