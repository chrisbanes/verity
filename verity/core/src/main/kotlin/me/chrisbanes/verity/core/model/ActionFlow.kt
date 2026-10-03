package me.chrisbanes.verity.core.model

import java.util.regex.PatternSyntaxException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.chrisbanes.verity.core.interaction.Interaction

/** Application context and the complete ordered list selected for execution. */
@Serializable
data class ActionFlow(val appId: String, val actions: List<Interaction>) {
  fun validate(): ActionFlow {
    if (appId.isBlank()) invalid()
    for (action in actions) {
      when (action) {
        is Interaction.KeyPress -> if (action.keyName.isBlank()) invalid()

        is Interaction.TapOnText -> validateSelector(action.text)

        is Interaction.TapOnId -> validateSelector(action.resourceId)

        is Interaction.LongPressOnText -> validateSelector(action.text)

        is Interaction.WaitForAnimation -> if (action.timeoutMs != null && action.timeoutMs <= 0) invalid()

        is Interaction.WaitUntilVisible -> {
          if ((action.text == null) == (action.resourceId == null) || action.timeoutMs <= 0) invalid()
          validateSelector(action.text ?: checkNotNull(action.resourceId))
        }

        is Interaction.LaunchApp, is Interaction.InputText, Interaction.DefaultScroll -> Unit

        is Interaction.Scroll, is Interaction.Swipe,
        Interaction.LongPressOnFocused, Interaction.PullToRefresh,
        -> Unit
      }
    }
    return this
  }

  companion object {
    private val json = Json { classDiscriminator = "type" }

    fun decodeResponse(appId: String, response: String): ActionFlow {
      val decoded = try {
        val root = json.parseToJsonElement(response) as? JsonObject ?: invalid()
        val actions = root["actions"] as? JsonArray ?: invalid()
        for (element in actions) {
          val action = element as? JsonObject ?: invalid()
          for ((name, value) in action) {
            if (name in setOf("type", "keyName", "text", "resourceId", "direction") &&
              (value !is JsonPrimitive || !value.isString)
            ) {
              invalid()
            }
            if (name == "timeoutMs" && value != JsonNull &&
              (value !is JsonPrimitive || value.isString || value.intOrNull == null)
            ) {
              invalid()
            }
            if (name == "clearState" && value != JsonNull &&
              (value !is JsonPrimitive || value.isString || value.booleanOrNull == null)
            ) {
              invalid()
            }
          }
        }
        json.decodeFromJsonElement(Response.serializer(), root)
      } catch (_: SerializationException) {
        throw InvalidActionFlowException(ActionFlowInvalidReason.MALFORMED_RESPONSE)
      } catch (_: IllegalArgumentException) {
        throw InvalidActionFlowException(ActionFlowInvalidReason.MALFORMED_RESPONSE)
      }
      return ActionFlow(appId, decoded.actions).validate()
    }

    private fun validateSelector(selector: String) {
      if (selector.isBlank()) invalid()
      try {
        Regex(selector)
      } catch (_: PatternSyntaxException) {
        invalid()
      }
    }

    private fun invalid(): Nothing = throw InvalidActionFlowException(ActionFlowInvalidReason.INVALID_ACTIONS)
  }

  @Serializable
  private data class Response(val actions: List<Interaction>)
}

/** Fixed diagnostics intentionally exclude response contents and raw causes. */
class InvalidActionFlowException(val reason: ActionFlowInvalidReason) : Exception("Invalid action flow: ${reason.name}")

enum class ActionFlowInvalidReason {
  MALFORMED_RESPONSE,
  INVALID_ACTIONS,
  UNSUPPORTED_KEY,
  UNSUPPORTED_INTERACTION,
}
