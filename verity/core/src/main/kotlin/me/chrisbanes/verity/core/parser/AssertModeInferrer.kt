package me.chrisbanes.verity.core.parser

import me.chrisbanes.verity.core.model.AssertMode

object AssertModeInferrer {

  private val LOOK_WORDS = setOf("look", "looks", "looked")

  private val APPEARANCE_STEMS = listOf(
    "color",
    "colour",
    "gradient",
    "blur",
    "opacity",
    "shadow",
    "animat",
  )

  fun infer(description: String): AssertMode {
    val words = description.lowercase().split("\\s+".toRegex())
      .map { word -> word.trim { !it.isLetterOrDigit() } }

    val isVisual = words.any { word ->
      word in LOOK_WORDS || APPEARANCE_STEMS.any { word.startsWith(it) }
    }
    return if (isVisual) AssertMode.VISUAL else AssertMode.TREE
  }
}
