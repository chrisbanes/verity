package me.chrisbanes.verity.core.model

sealed interface JourneyStep {
  data class Wait(val until: String, val timeoutSeconds: Int = 20) : JourneyStep {
    init {
      require(until.isNotBlank()) { "Wait condition must not be blank" }
      require(!until.trim().equals("visually", ignoreCase = true)) { "Visual wait condition must not be blank" }
      require(timeoutSeconds > 0) { "Wait timeout must be positive" }
    }
  }

  data class Action(val instruction: String) : JourneyStep
  data class Assert(val description: String, val mode: AssertMode) : JourneyStep
  data class Loop(val action: String, val until: String, val max: Int = 20) : JourneyStep {
    val actionInstructions: List<String> get() = action.split(';').map(String::trim)

    init {
      require(actionInstructions.none(String::isEmpty)) { "Loop action components must not be empty" }
    }
  }
}
