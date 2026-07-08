package me.chrisbanes.verity.core.result

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Platform

@Serializable
data class JourneyArtifactIdentity(
  val name: String,
  val file: String,
  val app: String,
  @Serializable(with = PlatformWireSerializer::class) val platform: Platform,
)

@Serializable
data class AssertionArtifact(
  val description: String,
  @Serializable(with = AssertModeWireSerializer::class) val mode: AssertMode,
)

@Serializable
data class EvidenceArtifact(
  val type: EvidenceType,
  val path: String,
)

@Serializable
data class ArtifactError(
  val kind: ArtifactErrorKind,
  val message: String,
)

@Serializable
data class SegmentArtifactResult(
  val index: Int,
  val passed: Boolean,
  val executionMode: SegmentExecutionMode,
  val actions: List<String> = emptyList(),
  val assertion: AssertionArtifact? = null,
  val reasoning: String = "",
  val generatedFlows: List<String> = emptyList(),
  val evidence: List<EvidenceArtifact> = emptyList(),
  val error: ArtifactError? = null,
)

@Serializable
data class JourneyArtifactResult(
  val journey: JourneyArtifactIdentity,
  val passed: Boolean,
  val failedAt: Int? = null,
  val segments: List<SegmentArtifactResult> = emptyList(),
)

@Serializable
data class SuiteJourneyArtifact(
  val path: String,
  val name: String,
  val status: ArtifactStatus,
)

@Serializable
data class SuiteArtifactSummary(
  val formatVersion: Int,
  val timestamp: String,
  val inputPath: String,
  val status: ArtifactStatus,
  val total: Int,
  val passed: Int,
  val failed: Int,
  val journeys: List<SuiteJourneyArtifact> = emptyList(),
  val error: ArtifactError? = null,
  @Serializable(with = PlatformWireSerializer::class) val platform: Platform? = null,
  val provider: String? = null,
  val navigatorModel: String? = null,
  val inspectorModel: String? = null,
)

@Serializable
enum class ArtifactStatus {
  @SerialName("passed")
  PASSED,

  @SerialName("failed")
  FAILED,
}

@Serializable
enum class SegmentExecutionMode {
  @SerialName("fast")
  FAST,

  @SerialName("slow")
  SLOW,

  @SerialName("loop")
  LOOP,

  @SerialName("assertion-only")
  ASSERTION_ONLY,
}

@Serializable
enum class EvidenceType {
  @SerialName("flow")
  FLOW,

  @SerialName("screenshot")
  SCREENSHOT,

  @SerialName("hierarchy")
  HIERARCHY,
}

@Serializable
enum class ArtifactErrorKind {
  @SerialName("parser_failure")
  PARSER_FAILURE,

  @SerialName("setup_failure")
  SETUP_FAILURE,

  @SerialName("journey_failure")
  JOURNEY_FAILURE,
}

object PlatformWireSerializer : KSerializer<Platform> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Platform", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Platform) = encoder.encodeString(
    when (value) {
      Platform.ANDROID_TV -> "android-tv"
      Platform.ANDROID_MOBILE -> "android"
      Platform.IOS -> "ios"
    },
  )

  override fun deserialize(decoder: Decoder): Platform = when (val value = decoder.decodeString()) {
    "android-tv" -> Platform.ANDROID_TV
    "android" -> Platform.ANDROID_MOBILE
    "ios" -> Platform.IOS
    else -> error("Unknown platform: $value")
  }
}

object AssertModeWireSerializer : KSerializer<AssertMode> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AssertMode", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: AssertMode) = encoder.encodeString(value.name.lowercase())

  override fun deserialize(decoder: Decoder): AssertMode = when (val value = decoder.decodeString()) {
    "visible" -> AssertMode.VISIBLE
    "focused" -> AssertMode.FOCUSED
    "tree" -> AssertMode.TREE
    "visual" -> AssertMode.VISUAL
    else -> error("Unknown assert mode: $value")
  }
}
