package me.chrisbanes.verity.core.interaction

import me.chrisbanes.verity.core.model.Platform

interface InteractionMapper {
  fun map(instruction: String): Interaction?

  fun allMappable(instructions: List<String>): Boolean = instructions.all { map(it) != null }

  companion object {
    fun forPlatform(platform: Platform): InteractionMapper = when (platform) {
      Platform.ANDROID_TV -> AndroidTvInteractionMapper
      Platform.ANDROID_MOBILE -> AndroidMobileInteractionMapper
      Platform.IOS -> IosInteractionMapper
    }
  }
}
