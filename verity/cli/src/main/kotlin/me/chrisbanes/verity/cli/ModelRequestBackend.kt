package me.chrisbanes.verity.cli

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutorAPI
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.agent.ModelBackendFailure
import me.chrisbanes.verity.agent.redactModelDiagnostic

data class LabeledLocalImage(
  val label: String,
  val path: Path,
)

sealed interface ModelRequestSettings {
  data class Api(val params: LLMParams) : ModelRequestSettings

  data class Codex(val reasoningEffort: String?) : ModelRequestSettings
}

data class ModelRequest(
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

interface ModelRequestBackend {
  suspend fun execute(request: ModelRequest): Message.Assistant

  suspend fun close()
}

/** Records each request and its reply or redacted failure for `--model-trace`. */
internal class TracingModelRequestBackend(
  private val delegate: ModelRequestBackend,
  private val sink: ModelTraceSink,
) : ModelRequestBackend {
  private val ids = AtomicLong()

  override suspend fun execute(request: ModelRequest): Message.Assistant {
    val id = "#${ids.incrementAndGet()}"
    trace {
      write("$id start prompt=${request.promptName} model=${request.model.id} images=${request.images.size}")
      write("$id system ${request.systemText}")
      write("$id user ${request.userText}")
      request.images.forEach { write("$id image ${it.label} ${it.path}") }
    }
    val started = TimeSource.Monotonic.markNow()
    val response = try {
      delegate.execute(request)
    } catch (e: CancellationException) {
      throw e
    } catch (failure: Exception) {
      val kind = (failure as? ModelBackendFailure)?.let { " kind=${it.kind}" }.orEmpty()
      val message = redactModelDiagnostic("${failure::class.simpleName}: ${failure.message}")
      trace { write("$id failure duration=${started.elapsedNow().inWholeMilliseconds}ms$kind $message") }
      throw failure
    }
    trace { write("$id response finish=${response.finishReason} duration=${started.elapsedNow().inWholeMilliseconds}ms ${response.textContent()}") }
    return response
  }

  override suspend fun close() = delegate.close()

  // Non-cancellable so a timeout racing a finished request cannot drop its reply or trace line.
  private suspend fun trace(block: ModelTraceSink.() -> Unit) = withContext(NonCancellable + Dispatchers.IO) { sink.block() }

  private fun ModelTraceSink.write(text: String) = write("request", text)
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
