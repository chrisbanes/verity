package me.chrisbanes.verity.core.interaction

import me.chrisbanes.verity.core.context.KeyAction

/** Resolves project-context key actions to a raw keycode press before falling back to [builtIn]. */
class KeyActionInteractionMapper(
  keyActions: List<KeyAction>,
  private val builtIn: InteractionMapper,
) : InteractionMapper {
  private val byName = keyActions.associateBy { it.name.trim().lowercase() }

  fun keyActionFor(instruction: String): KeyAction? = byName[instruction.trim().lowercase()]

  override fun map(instruction: String): Interaction? = keyActionFor(instruction)?.let { Interaction.KeyCode(it.keycode, it.longPress) }
    ?: builtIn.map(instruction)
}
