package me.chrisbanes.verity.core.journey

import me.chrisbanes.verity.core.model.JourneySegment
import me.chrisbanes.verity.core.model.JourneyStep

object JourneySegmenter {

    fun segment(steps: List<JourneyStep>): List<JourneySegment> {
        val segments = mutableListOf<JourneySegment>()
        val pendingActions = mutableListOf<JourneyStep.Action>()

        for (step in steps) {
            when (step) {
                is JourneyStep.Action -> {
                    pendingActions.add(step)
                }
                is JourneyStep.Assert -> {
                    segments.add(
                        JourneySegment(
                            index = segments.size,
                            actions = pendingActions.toList(),
                            assertion = step,
                        ),
                    )
                    pendingActions.clear()
                }
                is JourneyStep.Loop -> {
                    // Flush pending actions as a separate segment
                    if (pendingActions.isNotEmpty()) {
                        segments.add(
                            JourneySegment(
                                index = segments.size,
                                actions = pendingActions.toList(),
                            ),
                        )
                        pendingActions.clear()
                    }
                    // Loop becomes its own segment
                    segments.add(
                        JourneySegment(
                            index = segments.size,
                            actions = emptyList(),
                            loop = step,
                        ),
                    )
                }
            }
        }

        // Trailing actions without an assertion
        if (pendingActions.isNotEmpty()) {
            segments.add(
                JourneySegment(
                    index = segments.size,
                    actions = pendingActions.toList(),
                ),
            )
        }

        return segments
    }
}
