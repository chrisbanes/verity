package me.chrisbanes.verity.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Platform {
  @SerialName("android-tv")
  ANDROID_TV,

  @SerialName("android")
  ANDROID_MOBILE,

  @SerialName("ios")
  IOS,
}
