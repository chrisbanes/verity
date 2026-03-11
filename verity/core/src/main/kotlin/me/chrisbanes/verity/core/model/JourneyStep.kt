package me.chrisbanes.verity.core.model

sealed interface JourneyStep {
    data class Action(val instruction: String) : JourneyStep
    data class Assert(val description: String, val mode: AssertMode) : JourneyStep
    data class Loop(val action: String, val until: String, val max: Int = 20) : JourneyStep
}
