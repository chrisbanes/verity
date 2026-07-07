package me.chrisbanes.verity.core.model

enum class AssertionStrategy {
  INFER,
  VISIBLE,
  FOCUSED,
  TREE,
  VISUAL,
  ;

  companion object {
    val validNames: List<String> = entries.map { it.configName }

    fun fromConfig(value: String): AssertionStrategy {
      val normalized = value.trim().lowercase()
      return entries.firstOrNull { it.configName == normalized }
        ?: throw IllegalArgumentException(
          "Invalid assertions.strategy '$value'. Expected one of: ${validNames.joinToString()}",
        )
    }
  }
}

val AssertionStrategy.configName: String
  get() = name.lowercase().replace('_', '-')
