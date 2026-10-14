package me.chrisbanes.verity.core.interaction

object AndroidTvInteractionMapper : InteractionMapper {

  /** Fast-path instruction to Maestro key name. Public so the device layer can check every name resolves. */
  val KEY_MAP: Map<String, String> = mapOf(
    "press d-pad down" to "Remote Dpad Down",
    "press d-pad up" to "Remote Dpad Up",
    "press d-pad left" to "Remote Dpad Left",
    "press d-pad right" to "Remote Dpad Right",
    "press d-pad center" to "Remote Dpad Center",
    "press select" to "Remote Dpad Center",
    "press enter" to "Remote Dpad Center",
    "press back" to "back",
    "press home" to "home",
    "press menu" to "Remote Menu",
    "press play" to "Remote Media Play Pause",
    "press pause" to "Remote Media Play Pause",
    "press rewind" to "Remote Media Rewind",
    "press fast forward" to "Remote Media Fast Forward",
  )

  override fun map(instruction: String): Interaction? {
    RawKeycodeInstruction.parse(instruction)?.let { return it }
    val keyName = KEY_MAP[instruction.trim().lowercase()] ?: return null
    return Interaction.KeyPress(keyName)
  }
}
