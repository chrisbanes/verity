package me.chrisbanes.verity.core.interaction

object IosInteractionMapper : InteractionMapper {

  private val KEY_MAP = mapOf(
    "press home" to "home",
    "press lock" to "lock",
    "press volume up" to "volume up",
    "press volume down" to "volume down",
  )

  override fun map(instruction: String): Interaction? = TouchInteractionParser.parse(instruction, KEY_MAP)
}
