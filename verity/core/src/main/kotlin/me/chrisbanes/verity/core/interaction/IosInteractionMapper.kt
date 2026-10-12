package me.chrisbanes.verity.core.interaction

object IosInteractionMapper : InteractionMapper {

  private val KEY_MAP = mapOf(
    "press home" to "home",
    "press lock" to "lock",
    "press volume up" to "volume up",
    "press volume down" to "volume down",
  )

  // Raw keycode steps are Android-only: leave them unmapped rather than parse "long press keycode N" as a text long press.
  override fun map(instruction: String): Interaction? = if (RawKeycodeInstruction.parse(instruction) != null) null else TouchInteractionParser.parse(instruction, KEY_MAP)
}
