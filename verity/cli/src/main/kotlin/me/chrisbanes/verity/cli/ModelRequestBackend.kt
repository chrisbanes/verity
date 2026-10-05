package me.chrisbanes.verity.cli

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutorAPI
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LabeledLocalImage(
  val label: String,
  val path: Path,
)

internal sealed interface ModelRequestSettings {
  data class Api(val params: LLMParams) : ModelRequestSettings

  data class Codex(val reasoningEffort: String?) : ModelRequestSettings
}

internal data class ModelRequest(
  val promptName: String,
  val systemText: String,
  val userText: String,
  val images: List<LabeledLocalImage>,
  val model: SelectedRoleModel,
  val settings: ModelRequestSettings,
) {
  init {
    require(
      (model is SelectedRoleModel.Api && settings is ModelRequestSettings.Api) ||
        (model is SelectedRoleModel.Codex && settings is ModelRequestSettings.Codex),
    ) { "Model request settings must match the selected backend" }
  }
}

internal interface ModelRequestBackend {
  suspend fun execute(request: ModelRequest): Message.Assistant

  suspend fun close()
}

/** Executes API-backed requests through Koog while retaining complete native LLM parameters. */
internal class ApiModelRequestBackend(
  private val executor: PromptExecutorAPI,
) : ModelRequestBackend {
  override suspend fun execute(request: ModelRequest): Message.Assistant {
    val model = request.model as? SelectedRoleModel.Api
      ?: error("API backend requires an API model")
    val settings = request.settings as? ModelRequestSettings.Api
      ?: error("API backend requires API settings")
    val prompt = withContext(Dispatchers.IO) { buildPrompt(request, settings.params) }
    return executor.execute(prompt, model.model, emptyList())
  }

  override suspend fun close() {
    withContext(Dispatchers.IO) { executor.close() }
  }

  private fun buildPrompt(request: ModelRequest, params: LLMParams) = prompt(request.promptName, params = params) {
    system(request.systemText)
    if (request.images.isEmpty()) {
      user(request.userText)
    } else {
      user {
        text(request.userText)
        request.images.forEach { image ->
          text(image.label)
          image(kotlinx.io.files.Path(image.path.toString()))
        }
      }
    }
  }
}
