package me.chrisbanes.verity.agent

import ai.koog.prompt.message.Message
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Only these diagnostics may leave a model request boundary. Never retain a raw cause. */
class ModelFailureException(val stage: ModelRequestStage, val failure: ModelFailureKind) : Exception("${stage.diagnostic} ${failure.diagnostic}")

enum class ModelRequestStage(val diagnostic: String) {
  NAVIGATOR_FLOW("Navigator flow"),
  NAVIGATOR_SCROLL("Navigator scroll"),
  INSPECTOR_TREE("Inspector tree"),
  INSPECTOR_VISUAL("Inspector visual"),
}

enum class ModelFailureKind(val diagnostic: String) {
  REQUEST("request failed"),
  TIMEOUT("request timed out"),
  TRUNCATED("response was truncated"),
  EMPTY("response was empty"),
  INVALID_RESPONSE("response was invalid"),
  INVALID_VERDICT("response had an invalid verdict"),
}

/** Defense in depth for authored diagnostics; raw request errors must use fixed stages instead. */
fun redactModelDiagnostic(message: String): String = message
  .replace(Regex("""(?i)Bearer\s+[^\s,;]+"""), "Bearer [redacted]")
  .replace(Regex("""\bsk-[A-Za-z0-9_-]+\b"""), "[redacted]")
  .replace(Regex("""\bAIza[A-Za-z0-9_-]+\b"""), "[redacted]")
  .replace(Regex("""\beyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"""), "[redacted]")
  .replace(Regex("""(?i)(api[_-]?key\s*[:=]\s*)[^\s,;]+"""), "$1[redacted]")

/** Only the backend callback is inside request-failure conversion. Decoding and I/O stay outside. */
internal suspend fun requestModelText(stage: ModelRequestStage, execute: suspend () -> Message.Assistant): String = requestModelText(stage, null, execute)

internal suspend fun requestModelText(
  stage: ModelRequestStage,
  onFailure: ((ModelFailureException) -> Unit)?,
  execute: suspend () -> Message.Assistant,
): String {
  try {
    val response = try {
      if (onFailure == null) withTimeoutOrNull(30.seconds) { execute() } else observedRequest(stage, onFailure, execute)
    } catch (error: CancellationException) {
      throw error
    } catch (failure: ModelFailureException) {
      throw failure
    } catch (_: Exception) {
      throw ModelFailureException(stage, ModelFailureKind.REQUEST)
    } ?: throw ModelFailureException(stage, ModelFailureKind.TIMEOUT)
    if (response.finishReason?.lowercase() in setOf("length", "max_tokens", "incomplete")) {
      throw ModelFailureException(stage, ModelFailureKind.TRUNCATED)
    }
    val text = response.textContent()
    if (text.isBlank()) throw ModelFailureException(stage, ModelFailureKind.EMPTY)
    return text
  } catch (failure: ModelFailureException) {
    onFailure?.invoke(failure)
    throw failure
  }
}

private class OwnedModelTimeout : CancellationException("Owned model request deadline expired")

/** Record our timer before joining a callback whose cancellation cleanup may suspend. */
private suspend fun observedRequest(
  stage: ModelRequestStage,
  onFailure: (ModelFailureException) -> Unit,
  execute: suspend () -> Message.Assistant,
): Message.Assistant = supervisorScope {
  val request = async { execute() }
  val failure = ModelFailureException(stage, ModelFailureKind.TIMEOUT)
  val timer = launch {
    delay(30.seconds)
    onFailure(failure)
    request.cancel(OwnedModelTimeout())
  }
  try {
    request.await()
  } catch (owned: OwnedModelTimeout) {
    throw failure
  } finally {
    withContext(NonCancellable) {
      timer.cancelAndJoin()
      request.cancelAndJoin()
    }
  }
}
