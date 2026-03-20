package me.chrisbanes.verity.core.interaction

object AndroidMobileInteractionMapper : InteractionMapper {

  private val KEY_MAP = mapOf(
    "press back" to "back",
    "press home" to "home",
    "press enter" to "enter",
    "press volume up" to "volume up",
    "press volume down" to "volume down",
  )

  override fun map(instruction: String): Interaction? = TouchInteractionParser.parse(instruction, KEY_MAP)
}
