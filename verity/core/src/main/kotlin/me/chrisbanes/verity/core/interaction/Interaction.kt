package me.chrisbanes.verity.core.interaction

enum class Direction {
  UP,
  DOWN,
  LEFT,
  RIGHT,
}

sealed interface Interaction {
  data class KeyPress(val keyName: String) : Interaction
  data class TapOnText(val text: String) : Interaction
  data class TapOnId(val resourceId: String) : Interaction
  data class Scroll(val direction: Direction) : Interaction
  data class Swipe(val direction: Direction) : Interaction
  data object LongPressOnFocused : Interaction
  data class LongPressOnText(val text: String) : Interaction
  data object PullToRefresh : Interaction
}
