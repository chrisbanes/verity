package me.chrisbanes.verity.core.keymap

import me.chrisbanes.verity.core.model.Platform

interface PlatformKeyMapper {
  fun map(instruction: String): String?

  fun allMappable(instructions: List<String>): Boolean = instructions.all { map(it) != null }

  companion object {
    fun forPlatform(platform: Platform): PlatformKeyMapper = when (platform) {
      Platform.ANDROID_TV -> AndroidTvKeyMapper
      Platform.ANDROID_MOBILE -> AndroidMobileKeyMapper
      Platform.IOS -> IosKeyMapper
    }
  }
}
