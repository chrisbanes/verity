package me.chrisbanes.verity.core.keymap

object AndroidTvKeyMapper : PlatformKeyMapper {

  private val KEY_MAP = mapOf(
    "press d-pad down" to "Remote Dpad Down",
    "press d-pad up" to "Remote Dpad Up",
    "press d-pad left" to "Remote Dpad Left",
    "press d-pad right" to "Remote Dpad Right",
    "press d-pad center" to "Remote Dpad Center",
    "press select" to "Remote Dpad Center",
    "press enter" to "Remote Dpad Center",
    "press back" to "back",
    "press home" to "home",
    "press menu" to "Remote Media Menu",
    "press play" to "Remote Media Play Pause",
    "press pause" to "Remote Media Play Pause",
    "press rewind" to "Remote Media Rewind",
    "press fast forward" to "Remote Media Fast Forward",
  )

  override fun map(instruction: String): String? = KEY_MAP[instruction.trim().lowercase()]
}
