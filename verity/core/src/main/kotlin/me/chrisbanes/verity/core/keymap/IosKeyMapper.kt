package me.chrisbanes.verity.core.keymap

object IosKeyMapper : PlatformKeyMapper {

  private val KEY_MAP = mapOf(
    "press home" to "home",
    "press lock" to "lock",
    "press volume up" to "volume up",
    "press volume down" to "volume down",
  )

  override fun map(instruction: String): String? = KEY_MAP[instruction.trim().lowercase()]
}
