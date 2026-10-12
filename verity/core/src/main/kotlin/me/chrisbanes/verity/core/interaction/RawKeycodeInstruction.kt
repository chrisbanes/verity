package me.chrisbanes.verity.core.interaction

/** Parses the whole instruction `press keycode N` or `long press keycode N` (Android only). */
internal object RawKeycodeInstruction {
  private val PATTERN = Regex("^(long )?press keycode (\\d+)$")

  fun parse(instruction: String): Interaction.KeyCode? {
    val match = PATTERN.matchEntire(instruction.trim().lowercase()) ?: return null
    val keycode = match.groupValues[2].toIntOrNull() ?: return null
    return Interaction.KeyCode(keycode, longPress = match.groupValues[1].isNotEmpty())
  }
}
