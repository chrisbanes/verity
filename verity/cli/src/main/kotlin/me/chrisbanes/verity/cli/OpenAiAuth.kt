package me.chrisbanes.verity.cli

/** The authentication modes available for OpenAI models. */
enum class OpenAiAuth(val value: String) {
  API_KEY("api-key"),
  CHATGPT("chatgpt"),

  ;

  companion object {
    /** Parses an explicit CLI/config value, defaulting an omitted value to API-key auth. */
    fun parse(value: String?): OpenAiAuth = when (value) {
      null -> API_KEY
      "api-key" -> API_KEY
      "chatgpt" -> CHATGPT
      else -> throw IllegalArgumentException("Unknown OpenAI authentication mode '$value'")
    }
  }
}
