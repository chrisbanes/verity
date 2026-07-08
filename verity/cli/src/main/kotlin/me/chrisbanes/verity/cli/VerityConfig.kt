package me.chrisbanes.verity.cli

import ai.koog.prompt.llm.LLModel
import com.charleskorn.kaml.Yaml
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VerityConfig(
  val paths: VerityPathsConfig? = null,
  val device: VerityDeviceConfig? = null,
  val llm: VerityLlmConfig? = null,
  val assertions: VerityAssertionsConfig? = null,
  val provider: String? = null,
  @SerialName("navigator-model") val navigatorModel: String? = null,
  @SerialName("inspector-model") val inspectorModel: String? = null,
  @SerialName("require-context") val requireContext: Boolean? = null,
) {
  val effectiveProvider: String?
    get() = llm?.provider ?: provider

  val effectiveNavigatorModel: String?
    get() = llm?.navigatorModel ?: navigatorModel

  val effectiveInspectorModel: String?
    get() = llm?.inspectorModel ?: inspectorModel

  companion object {
    fun fromYaml(yaml: String): VerityConfig = Yaml.default.decodeFromString(serializer(), yaml)

    fun loadOrDefault(file: File): VerityConfig {
      if (!file.exists()) return VerityConfig()
      val text = file.readText().trim()
      return if (text.isEmpty()) VerityConfig() else fromYaml(text)
    }
  }
}

fun resolveRequiredContext(cliRequireContext: Boolean, config: VerityConfig): Boolean = cliRequireContext || config.requireContext == true

@Serializable
data class VerityPathsConfig(
  val journeys: String? = null,
  val context: String? = null,
  val output: String? = null,
)

@Serializable
data class VerityDeviceConfig(
  val platform: String? = null,
  val id: String? = null,
  @SerialName("disable-animations") val disableAnimations: Boolean? = null,
)

@Serializable
data class VerityLlmConfig(
  val provider: String? = null,
  @SerialName("navigator-model") val navigatorModel: String? = null,
  @SerialName("inspector-model") val inspectorModel: String? = null,
)

@Serializable
data class VerityAssertionsConfig(
  val strategy: String? = null,
)

fun resolveProvider(cliProvider: String?, config: VerityConfig): VerityProvider {
  val name = cliProvider ?: config.effectiveProvider ?: "anthropic"
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
