package me.chrisbanes.verity.core.parser

import me.chrisbanes.verity.core.model.JourneyStep

object LoopStepInferrer {

    private val LOOP_VERBS = setOf("press", "navigate", "move", "scroll", "go", "step")

    // Matches: <action> until <condition> [up to N times]
    private val PATTERN = Regex(
        """^(.+?)\s+until\s+(.+?)(?:\s+up\s+to\s+(\d+)\s+times?)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun infer(text: String): JourneyStep.Loop? {
        val match = PATTERN.matchEntire(text.trim()) ?: return null
        val action = match.groupValues[1].trim()
        val until = match.groupValues[2].trim()
        val max = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 20

        // First word of action must be an allowed verb
        val verb = action.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: return null
        if (verb !in LOOP_VERBS) return null

        return JourneyStep.Loop(action = action, until = until, max = max)
    }
}
