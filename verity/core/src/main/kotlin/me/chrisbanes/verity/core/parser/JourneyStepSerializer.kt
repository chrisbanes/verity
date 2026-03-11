package me.chrisbanes.verity.core.parser

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.JourneyStep

/**
 * Pure parsing logic for journey step strings. Used by the Kaml serializer
 * and available for direct use in tests.
 *
 * Priority chain:
 * 1. [?mode] prefix — pinned assertion mode
 * 2. [?] prefix — inferred assertion mode
 * 3. Loop inference — NL "verb ... until condition"
 * 4. Assertion inference — NL "Verify/Ensure/Confirm/Check..."
 * 5. Default — Action
 */
object JourneyStepParser {

  private val PINNED_ASSERT_PATTERN = Regex(
    """^\[\?(\w+)]\s+(.+)$""",
  )

  private val GENERIC_ASSERT_PATTERN = Regex(
    """^\[\?]\s+(.+)$""",
  )

  private val MODE_MAP = mapOf(
    "visual" to AssertMode.VISUAL,
    "tree" to AssertMode.TREE,
    "visible" to AssertMode.VISIBLE,
    "focused" to AssertMode.FOCUSED,
  )

  fun parse(text: String): JourneyStep {
    val trimmed = text.trim()

    // 1. [?mode] prefix
    PINNED_ASSERT_PATTERN.matchEntire(trimmed)?.let { match ->
      val modeName = match.groupValues[1].lowercase()
      val description = match.groupValues[2].trim()
      val mode = MODE_MAP[modeName]
        ?: error("Unknown assert mode: $modeName. Valid modes: ${MODE_MAP.keys}")
      return JourneyStep.Assert(description = description, mode = mode)
    }

    // 2. [?] prefix
    GENERIC_ASSERT_PATTERN.matchEntire(trimmed)?.let { match ->
      val description = match.groupValues[1].trim()
      val mode = AssertModeInferrer.infer(description)
      return JourneyStep.Assert(description = description, mode = mode)
    }

    // 3. Loop inference
    LoopStepInferrer.infer(trimmed)?.let { return it }

    // 4. Assertion inference (NL keywords)
    AssertionStepInferrer.infer(trimmed)?.let { return it }

    // 5. Default: Action
    return JourneyStep.Action(instruction = trimmed)
  }
}

/**
 * Kaml serializer that deserializes YAML string values into JourneyStep
 * using JourneyStepParser.
 */
object JourneyStepSerializer : KSerializer<JourneyStep> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("JourneyStep", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): JourneyStep {
    val text = decoder.decodeString()
    return JourneyStepParser.parse(text)
  }

  override fun serialize(encoder: Encoder, value: JourneyStep) {
    val text = when (value) {
      is JourneyStep.Action -> value.instruction

      is JourneyStep.Assert -> {
        val modeStr = value.mode.name.lowercase()
        "[?$modeStr] ${value.description}"
      }

      is JourneyStep.Loop -> {
        val maxStr = if (value.max != 20) " up to ${value.max} times" else ""
        "${value.action} until ${value.until}$maxStr"
      }
    }
    encoder.encodeString(text)
  }
}
