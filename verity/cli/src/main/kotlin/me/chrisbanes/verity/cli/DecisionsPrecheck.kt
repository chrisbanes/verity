package me.chrisbanes.verity.cli

import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import me.chrisbanes.verity.agent.ConditionPrecheck
import me.chrisbanes.verity.agent.PrecheckState
import me.chrisbanes.verity.agent.PrecheckVerdict
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.timing.NoOpPhaseTimer
import me.chrisbanes.verity.core.timing.PhaseKind
import me.chrisbanes.verity.core.timing.PhaseTimer

/** Picks the pre-check client for [config]'s provider. */
fun createPrecheck(
  config: ResolvedPrecheck,
  stats: PrecheckStats,
  trace: ModelTraceSink?,
  timer: PhaseTimer,
  warn: (String) -> Unit,
): ConditionPrecheck = when (config.provider) {
  PrecheckProvider.SYSTEM_ONE -> SystemOnePrecheck(config, stats, trace, timer, warn)
  PrecheckProvider.DECISIONS -> DecisionsPrecheck(config, stats, trace, timer, warn)
}

/**
 * Asks OpenAI's Decisions API (public beta) for the probability that a condition holds on the current tree or screenshot.
 * Every non-cancellation failure, including image preparation, falls back to the inspector by returning null; a `refusal`
 * answer is an uncertain fallback. The API key only ever leaves in the `Authorization` header, and neither it nor image
 * data appears in warnings, traces or exceptions.
 */
class DecisionsPrecheck(
  private val config: ResolvedPrecheck,
  private val stats: PrecheckStats,
  private val trace: ModelTraceSink? = null,
  private val timer: PhaseTimer = NoOpPhaseTimer,
  private val warn: (String) -> Unit = { System.err.println(it) },
  private val send: suspend (HttpRequest) -> HttpResponse<String> = defaultSend(config),
) : ConditionPrecheck {
  private val uri = URI("${config.baseUrl}/v1/decisions")
  private val host = uri.host + if (uri.port != -1) ":${uri.port}" else ""
  private val ids = AtomicLong()
  private val warned = AtomicBoolean()

  private class Failure(val kind: String) : Exception(kind)

  /** A null [probability] is a `refusal`. */
  private class Answer(val probability: Double?, val model: String)

  override suspend fun check(state: PrecheckState, condition: String, acceptFail: Boolean): PrecheckVerdict? {
    val id = "P${ids.incrementAndGet()}"
    val texts = buildList {
      state.hierarchy?.let { add("Current accessibility tree:\n$it") }
      if (state.referenceText.isNotEmpty()) add("Earlier observations (reference only, not proof of the current state):\n${state.referenceText}")
    }
    trace(
      "$id start prompt=precheck model=${config.model} host=$host",
      "$id question $condition",
      *texts.map { "$id input ${it.replace('\n', ' ')}" }.toTypedArray(),
    )
    val started = TimeSource.Monotonic.markNow()
    fun elapsed() = "duration=${started.elapsedNow().inWholeMilliseconds}ms"
    try {
      val answer = timer.phase(PhaseKind.MODEL_REQUEST, "precheck") {
        // The image is read after the trace, so its data never reaches it.
        val image = state.screenshot?.let { Part("input_image", imageUrl = readImage(it)) }
        query(state.platform, condition, listOfNotNull(image) + texts.map { Part("input_text", text = it) })
      }
      val probability = answer.probability
      if (probability == null) {
        trace("$id response ${elapsed()} model=${answer.model} outcome=refused")
        stats.uncertain()
        return null
      }
      val passed = probability >= config.passAt
      val outcome = when {
        passed -> "passed"
        probability <= config.failAt -> "failed"
        else -> "uncertain"
      }
      // A failure the caller cannot use is no decision: it must not count as accepted, and the trace says so.
      val discarded = outcome == "failed" && !acceptFail
      trace("$id response ${elapsed()} model=${answer.model} predicate=$probability outcome=${if (discarded) "failed-discarded" else outcome}")
      if (outcome == "uncertain" || discarded) {
        stats.uncertain()
        return null
      }
      stats.accepted()
      return PrecheckVerdict(passed, probability, answer.model, host)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Failure) {
      stats.error()
      trace("$id failure ${elapsed()} ${e.kind}")
      if (warned.compareAndSet(false, true)) warn("Warning: pre-check request to $host failed (${e.kind}); falling back to the inspector")
      return null
    }
  }

  private suspend fun query(platform: Platform, condition: String, parts: List<Part>): Answer {
    val evidence = if (parts.any { it.type == "input_image" }) "screenshot" else "accessibility tree"
    val request = DecisionsRequest(
      config.model,
      listOf(Message("user", parts)),
      listOf(
        Question(
          "predicate",
          "condition",
          "Using the current $evidence of an ${platform.precheckLabel()} app, what is the probability that this condition currently holds? " +
            "Earlier observations are reference only, not proof. Condition: $condition",
        ),
      ),
    )
    val response = try {
      // Built inside the guard: the JDK rejects a malformed header value with a message that would quote the key.
      val httpRequest = HttpRequest.newBuilder(uri)
        .timeout(config.timeout.toJavaDuration())
        .header("Content-Type", "application/json")
        .apply { config.apiKey?.let { header("Authorization", "Bearer $it") } }
        .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(DecisionsRequest.serializer(), request)))
        .build()
      withTimeoutOrNull(config.timeout) { send(httpRequest) } ?: throw Failure("timeout")
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
    val decoded = try {
      lenientJson.decodeFromString(DecisionsResponse.serializer(), response.body())
    } catch (_: SerializationException) {
      throw Failure("invalid response")
    } catch (_: IllegalArgumentException) {
      throw Failure("invalid response")
    }
    val model = decoded.model?.takeIf { it.isNotBlank() } ?: config.model
    val answer = decoded.answers.firstOrNull { it.name == "condition" }
    if (answer?.type == "refusal") return Answer(null, model)
    val probability = answer?.takeIf { it.type == "predicate" }?.probability?.takeIf { !it.isString }?.doubleOrNull
    if (probability == null || !probability.isFinite() || probability !in 0.0..1.0) throw Failure("invalid response")
    return Answer(probability, model)
  }

  /** Reads [path] unscaled as a PNG data URL; any failure is [Failure] `image`. */
  private suspend fun readImage(path: Path): String = try {
    withContext(Dispatchers.IO) {
      val bytes = Files.readAllBytes(path)
      if (bytes.size < PNG_SIGNATURE.size || !bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) throw Failure("image")
      "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Failure) {
    throw e
  } catch (_: Exception) {
    throw Failure("image")
  }

  // Non-cancellable so a racing cancellation cannot drop a diagnostic line; blocking file writes stay off the caller's dispatcher.
  private suspend fun trace(vararg lines: String) {
    val sink = trace ?: return
    withContext(NonCancellable + Dispatchers.IO) { lines.forEach { sink.write("precheck", it) } }
  }

  @Serializable
  private class DecisionsRequest(val model: String, val input: List<Message>, val questions: List<Question>)

  @Serializable
  private class Message(val role: String, val content: List<Part>)

  /** The default [Json] omits the unset field, so a text part has no `image_url` and an image part no `text`. */
  @Serializable
  private class Part(val type: String, val text: String? = null, @SerialName("image_url") val imageUrl: String? = null)

  @Serializable
  private class Question(val type: String, val name: String, val instructions: String)

  @Serializable
  private class DecisionsResponse(val model: String? = null, val answers: List<DecisionsAnswer> = emptyList())

  @Serializable
  private class DecisionsAnswer(val type: String? = null, val name: String? = null, val probability: JsonPrimitive? = null)

  private companion object {
    val lenientJson = Json { ignoreUnknownKeys = true }
    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
  }
}
