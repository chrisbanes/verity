package me.chrisbanes.verity.core.interaction

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Direction {
  UP,
  DOWN,
  LEFT,
  RIGHT,
}

@Serializable
sealed interface Interaction {
  @Serializable
  @SerialName("keyPress")
  data class KeyPress(val keyName: String) : Interaction

  @Serializable
  @SerialName("tapOnText")
  data class TapOnText(val text: String) : Interaction

  @Serializable
  @SerialName("tapOnId")
  data class TapOnId(val resourceId: String) : Interaction

  @Serializable
  @SerialName("scroll")
  data class Scroll(val direction: Direction) : Interaction

  @Serializable
  @SerialName("swipe")
  data class Swipe(val direction: Direction) : Interaction

  @Serializable
  @SerialName("longPressOnFocused")
  data object LongPressOnFocused : Interaction

  @Serializable
  @SerialName("longPressOnText")
  data class LongPressOnText(val text: String) : Interaction

  @Serializable
  @SerialName("pullToRefresh")
  data object PullToRefresh : Interaction

  @Serializable
  @SerialName("launchApp")
  data class LaunchApp(val clearState: Boolean? = null) : Interaction

  @Serializable
  @SerialName("inputText")
  data class InputText(val text: String) : Interaction

  @Serializable
  @SerialName("defaultScroll")
  data object DefaultScroll : Interaction

  @Serializable
  @SerialName("waitForAnimation")
  data class WaitForAnimation(val timeoutMs: Int? = null) : Interaction

  @Serializable
  @SerialName("waitUntilVisible")
  data class WaitUntilVisible(
    val text: String? = null,
    val resourceId: String? = null,
    val timeoutMs: Int,
  ) : Interaction
}
