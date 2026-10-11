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
  ;

  /** The stable wire name declared by [SerialName], e.g. `android-tv`. */
  val wireName: String get() = serializer().descriptor.getElementName(ordinal)

  companion object {
    fun fromWireName(value: String): Platform? = entries.firstOrNull { it.wireName == value }
  }
}
