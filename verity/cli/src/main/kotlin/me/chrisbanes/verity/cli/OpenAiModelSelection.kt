package me.chrisbanes.verity.cli

import ai.koog.prompt.llm.LLModel

/** A role model whose representation stays native to its selected backend. */
internal sealed interface SelectedRoleModel {
  data class Api(val model: LLModel) : SelectedRoleModel

  data class Codex(val modelId: String) : SelectedRoleModel
}

internal enum class ModelRole {
  NAVIGATOR,
  INSPECTOR,
}

/** Resolves CLI-over-config model selection without constructing API models for Codex IDs. */
internal fun selectRoleModel(
  provider: VerityProvider,
  auth: OpenAiAuth?,
  cliModelId: String?,
  configModelId: String?,
  role: ModelRole,
): SelectedRoleModel {
  if (auth != null && provider !== VerityProvider.OpenAI) {
    throw IllegalArgumentException("OpenAI authentication mode cannot be used with provider '${provider.name}'")
  }

  val selectedAuth = auth ?: OpenAiAuth.API_KEY
  val selectedId = cliModelId ?: configModelId

  return when (selectedAuth) {
    OpenAiAuth.API_KEY -> {
      val model = selectedId?.let(provider::findModel)
        ?: when (role) {
          ModelRole.NAVIGATOR -> provider.defaultNavigatorModel
          ModelRole.INSPECTOR -> provider.defaultInspectorModel
        }
      SelectedRoleModel.Api(model)
    }

    OpenAiAuth.CHATGPT -> SelectedRoleModel.Codex(selectedId ?: CHATGPT_DEFAULT_MODEL_ID)
  }
}

internal const val CHATGPT_DEFAULT_MODEL_ID = "gpt-6-luna"
