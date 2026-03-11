package me.chrisbanes.verity.core.model

data class JourneySegment(
    val index: Int,
    val actions: List<JourneyStep.Action>,
    val assertion: JourneyStep.Assert? = null,
    val loop: JourneyStep.Loop? = null,
)
