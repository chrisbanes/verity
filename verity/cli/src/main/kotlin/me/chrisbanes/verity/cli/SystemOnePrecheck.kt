package me.chrisbanes.verity.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import me.chrisbanes.verity.agent.ConditionPrecheck
import me.chrisbanes.verity.agent.PrecheckVerdict
import me.chrisbanes.verity.core.result.PrecheckSummaryArtifact
import me.chrisbanes.verity.core.timing.NoOpPhaseTimer
import me.chrisbanes.verity.core.timing.PhaseKind
import me.chrisbanes.verity.core.timing.PhaseTimer

/** Per-run pre-check outcome counters; error and uncertain fallbacks are kept apart. */
class PrecheckStats {
  private val acceptedCount = AtomicInteger()
  private val uncertainCount = AtomicInteger()
  private val errorCount = AtomicInteger()

  val accepted: Int get() = acceptedCount.get()
  val uncertainFallbacks: Int get() = uncertainCount.get()
  val errorFallbacks: Int get() = errorCount.get()

  internal fun accepted() = acceptedCount.incrementAndGet()

  internal fun uncertain() = uncertainCount.incrementAndGet()

  internal fun error() = errorCount.incrementAndGet()

  fun snapshot() = PrecheckSummaryArtifact(accepted, uncertainFallbacks, errorFallbacks)

  fun render() = "Pre-check: accepted $accepted, uncertain fallbacks $uncertainFallbacks, error fallbacks $errorFallbacks"
}

/**
 * Asks a System One server (TypeSafe Jev or compatible) whether a prose condition holds on the current tree.
 * Every non-cancellation failure falls back to the inspector by returning null; the API key only ever
 * leaves in the `Authorization` header and never appears in warnings, traces or exceptions.
 */
class SystemOnePrecheck(
  private val config: ResolvedPrecheck,
  private val trace: ModelTraceSink? = null,
  private val timer: PhaseTimer = NoOpPhaseTimer,
  private val warn: (String) -> Unit = { System.err.println(it) },
  private val send: suspend (HttpRequest) -> HttpResponse<String> = defaultSend(config),
) : ConditionPrecheck {
  val stats = PrecheckStats()

  private val uri = URI("${config.baseUrl}/v1/systemone")
  private val host = uri.host + if (uri.port != -1) ":${uri.port}" else ""
  private val ids = AtomicLong()
  private val warned = AtomicBoolean()

  private class Failure(val kind: String) : Exception(kind)

  override suspend fun check(state: String, condition: String, acceptFail: Boolean): PrecheckVerdict? {
    val id = "P${ids.incrementAndGet()}"
    trace("$id start prompt=precheck model=${config.model} host=$host", "$id question $condition", "$id state $state")
    val started = TimeSource.Monotonic.markNow()
    fun elapsed() = "duration=${started.elapsedNow().inWholeMilliseconds}ms"
    try {
      val (probability, model) = timer.phase(PhaseKind.MODEL_REQUEST, "precheck") { query(state, condition) }
      val passed = probability >= config.passAt
      val outcome = when {
        passed -> "passed"
        probability <= config.failAt -> "failed"
        else -> "uncertain"
      }
      // A failure the caller cannot use is no decision: it must not count as accepted, and the trace says so.
      val discarded = outcome == "failed" && !acceptFail
      trace("$id response ${elapsed()} model=$model noul=$probability outcome=${if (discarded) "failed-discarded" else outcome}")
      if (outcome == "uncertain" || discarded) {
        stats.uncertain()
        return null
      }
      stats.accepted()
      return PrecheckVerdict(passed, probability, model, host)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Failure) {
      stats.error()
      trace("$id failure ${elapsed()} ${e.kind}")
      if (warned.compareAndSet(false, true)) warn("Warning: pre-check request to $host failed (${e.kind}); falling back to the inspector")
      return null
    }
  }

  private suspend fun query(state: String, condition: String): Pair<Double, String> {
    val body = Json.encodeToString(SystemOneRequest.serializer(), SystemOneRequest(config.model, state, mapOf("condition" to NoulQuestion("noul", condition))))
    val response = try {
      // Built inside the guard: the JDK rejects a malformed header value with a message that would quote the key.
      val request = HttpRequest.newBuilder(uri)
        .timeout(config.timeout.toJavaDuration())
        .header("Content-Type", "application/json")
        .apply { config.apiKey?.let { header("Authorization", "Bearer $it") } }
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      withTimeoutOrNull(config.timeout) { send(request) } ?: throw Failure("timeout")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Failure) {
      throw e
    } catch (_: HttpTimeoutException) {
      throw Failure("timeout")
    } catch (e: Exception) {
      throw Failure(e::class.simpleName ?: "error")
    }
    if (response.statusCode() !in 200..299) throw Failure("HTTP ${response.statusCode()}")
    val answer = try {
      lenientJson.decodeFromString(SystemOneResponse.serializer(), response.body())
    } catch (_: SerializationException) {
      throw Failure("invalid response")
    } catch (_: IllegalArgumentException) {
      throw Failure("invalid response")
    }
    val primitive = answer.answers["condition"]?.noul
    val probability = primitive?.takeIf { !it.isString }?.doubleOrNull
    if (probability == null || !probability.isFinite() || probability !in 0.0..1.0) throw Failure("invalid response")
    return probability to (answer.model?.takeIf { it.isNotBlank() } ?: config.model)
  }

  // Non-cancellable so a racing cancellation cannot drop a diagnostic line; blocking file writes stay off the caller's dispatcher.
  private suspend fun trace(vararg lines: String) {
    val sink = trace ?: return
    withContext(NonCancellable + Dispatchers.IO) { lines.forEach { sink.write("precheck", it) } }
  }

  @Serializable
  private class SystemOneRequest(val model: String, val state: String, val questions: Map<String, NoulQuestion>)

  @Serializable
  private class NoulQuestion(val type: String, val instructions: String)

  @Serializable
  private class SystemOneResponse(val model: String? = null, val answers: Map<String, NoulAnswer> = emptyMap())

  @Serializable
  private class NoulAnswer(val noul: JsonPrimitive? = null)

  private companion object {
    val lenientJson = Json { ignoreUnknownKeys = true }

    // Redirects stay off so the bearer key is never forwarded to another host.
    fun defaultSend(config: ResolvedPrecheck): suspend (HttpRequest) -> HttpResponse<String> {
      val client = HttpClient.newBuilder().connectTimeout(config.timeout.toJavaDuration()).followRedirects(HttpClient.Redirect.NEVER).build()
      return { request -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await() }
    }
  }
}
