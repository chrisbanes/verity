package me.chrisbanes.verity.core.interaction

import me.chrisbanes.verity.core.context.KeyAction
import me.chrisbanes.verity.core.model.Platform

interface InteractionMapper {
  fun map(instruction: String): Interaction?

  /** One interaction per instruction, or null when any instruction needs the slow path. */
  fun mapAll(instructions: List<String>): List<Interaction>? = instructions.map { map(it) ?: return null }

  fun allMappable(instructions: List<String>): Boolean = mapAll(instructions) != null

  companion object {
    fun forPlatform(platform: Platform): InteractionMapper = when (platform) {
      Platform.ANDROID_TV -> AndroidTvInteractionMapper
      Platform.ANDROID_MOBILE -> AndroidMobileInteractionMapper
      Platform.IOS -> IosInteractionMapper
    }

    /** The platform mapper, with [keyActions] resolved first; the platform mapper itself when there are none. */
    fun forPlatform(platform: Platform, keyActions: List<KeyAction>): InteractionMapper = forPlatform(platform).let {
      if (keyActions.isEmpty()) it else KeyActionInteractionMapper(keyActions, it)
    }
  }
}
