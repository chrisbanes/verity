package me.chrisbanes.verity.cli

import ai.koog.prompt.llm.LLModel
import com.charleskorn.kaml.Yaml
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VerityConfig(
  val provider: String? = null,
  @SerialName("navigator-model") val navigatorModel: String? = null,
  @SerialName("inspector-model") val inspectorModel: String? = null,
) {
  companion object {
    fun fromYaml(yaml: String): VerityConfig = Yaml.default.decodeFromString(serializer(), yaml)

    fun loadOrDefault(file: File): VerityConfig {
      if (!file.exists()) return VerityConfig()
      val text = file.readText().trim()
      return if (text.isEmpty()) VerityConfig() else fromYaml(text)
    }
  }
}

fun resolveProvider(cliProvider: String?, config: VerityConfig): VerityProvider {
  val name = cliProvider ?: config.provider ?: "anthropic"
  return VerityProvider.fromName(name)
}

fun resolveModel(
  cliModel: String?,
  configModel: String?,
  default: LLModel,
  provider: VerityProvider,
): LLModel {
  val id = cliModel ?: configModel ?: return default
  return provider.findModel(id)
}
