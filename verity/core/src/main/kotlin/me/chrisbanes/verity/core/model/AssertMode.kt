package me.chrisbanes.verity.core.model

// Ordered by cost: VISIBLE and FOCUSED are free (deterministic),
// TREE uses text LLM, VISUAL uses vision LLM.
enum class AssertMode {
  VISIBLE,
  FOCUSED,
  TREE,
  VISUAL,
}
