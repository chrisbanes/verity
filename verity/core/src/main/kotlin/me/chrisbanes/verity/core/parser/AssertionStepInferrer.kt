package me.chrisbanes.verity.core.parser

import me.chrisbanes.verity.core.model.JourneyStep

object AssertionStepInferrer {

    private val PATTERN = Regex(
        """^(?:verify|ensure|confirm|check)\s+(?:that\s+)?(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    fun infer(text: String): JourneyStep.Assert? {
        val match = PATTERN.matchEntire(text.trim()) ?: return null
        val description = match.groupValues[1].trim()
        val mode = AssertModeInferrer.infer(description)
        return JourneyStep.Assert(description = description, mode = mode)
    }
}
