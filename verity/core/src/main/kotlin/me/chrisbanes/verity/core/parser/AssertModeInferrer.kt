package me.chrisbanes.verity.core.parser

import me.chrisbanes.verity.core.model.AssertMode

object AssertModeInferrer {

    private val VISUAL_KEYWORDS = setOf(
        "color", "colour", "highlight", "image", "icon", "animation",
        "gradient", "blur", "backdrop", "thumbnail", "poster", "artwork",
        "badge", "logo", "overlay", "opacity", "shadow", "border",
    )

    fun infer(description: String): AssertMode {
        val lower = description.lowercase()
        val words = lower.split("\\s+".toRegex()).filter { it.isNotEmpty() }

        if (words.any { it in VISUAL_KEYWORDS }) return AssertMode.VISUAL
        if (words.size <= 3) return AssertMode.VISIBLE
        return AssertMode.TREE
    }
}
