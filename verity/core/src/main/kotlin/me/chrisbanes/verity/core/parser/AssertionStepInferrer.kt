package me.chrisbanes.verity.core.parser

import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.JourneyStep

object AssertionStepInferrer {

  private val PATTERN = Regex(
    """^(?:verify|ensure|confirm|check)\s+(visually\s+)?(?:that\s+)?(.+)$""",
    RegexOption.IGNORE_CASE,
  )

  /** An explicit `visually` wins; otherwise [resolveMode] picks the mode from the description. */
  fun infer(
    text: String,
    resolveMode: (String) -> AssertMode = AssertModeInferrer::infer,
  ): JourneyStep.Assert? {
    val match = PATTERN.matchEntire(text.trim()) ?: return null
    val description = match.groupValues[2].trim()
    val mode = if (match.groups[1] != null) AssertMode.VISUAL else resolveMode(description)
    return JourneyStep.Assert(description = description, mode = mode)
  }
}
