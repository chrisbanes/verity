package me.chrisbanes.verity.core.interaction

object AndroidMobileInteractionMapper : InteractionMapper {

  private val KEY_MAP = mapOf(
    "press back" to "back",
    "press home" to "home",
    "press enter" to "enter",
    "press volume up" to "volume up",
    "press volume down" to "volume down",
  )

  private val SCROLL_DIRECTIONS = mapOf(
    "up" to Direction.UP,
    "down" to Direction.DOWN,
  )

  private val SWIPE_DIRECTIONS = mapOf(
    "up" to Direction.UP,
    "down" to Direction.DOWN,
    "left" to Direction.LEFT,
    "right" to Direction.RIGHT,
  )

  private val TAP_PREFIXES = listOf("tap on ", "tap ", "click on ", "click ")
  private val LONG_PRESS_PREFIXES = listOf("long press on ", "long press ", "hold ")

  override fun map(instruction: String): Interaction? {
    val normalized = instruction.trim()
    val lower = normalized.lowercase()

    // Key press — exact match
    KEY_MAP[lower]?.let { return Interaction.KeyPress(it) }

    // Pull to refresh
    if (lower == "pull to refresh") return Interaction.PullToRefresh

    // Scroll
    if (lower.startsWith("scroll ")) {
      val dir = lower.removePrefix("scroll ")
      SCROLL_DIRECTIONS[dir]?.let { return Interaction.Scroll(it) }
    }

    // Swipe
    if (lower.startsWith("swipe ")) {
      val dir = lower.removePrefix("swipe ")
      SWIPE_DIRECTIONS[dir]?.let { return Interaction.Swipe(it) }
    }

    // Long press / hold — extract text from ORIGINAL casing
    LONG_PRESS_PREFIXES.firstOrNull { lower.startsWith(it) }?.let { prefix ->
      val text = extractText(normalized.substring(prefix.length))
      return Interaction.LongPressOnText(text)
    }

    // Tap / click — extract text from ORIGINAL casing
    TAP_PREFIXES.firstOrNull { lower.startsWith(it) }?.let { prefix ->
      val text = extractText(normalized.substring(prefix.length))
      return Interaction.TapOnText(text)
    }

    return null
  }
}

/** Strip surrounding quotes from extracted text if present. */
private fun extractText(raw: String): String {
  val trimmed = raw.trim()
  if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
    return trimmed.substring(1, trimmed.length - 1)
  }
  return trimmed
}
