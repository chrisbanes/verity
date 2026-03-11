package me.chrisbanes.verity.core.keymap

object AndroidMobileKeyMapper : PlatformKeyMapper {

    private val KEY_MAP = mapOf(
        "press back" to "back",
        "press home" to "home",
        "press enter" to "enter",
        "press volume up" to "volume up",
        "press volume down" to "volume down",
    )

    override fun map(instruction: String): String? =
        KEY_MAP[instruction.trim().lowercase()]
}
