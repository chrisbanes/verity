package me.chrisbanes.verity.cli

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
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
 * Asks a System One server (TypeSafe Jev or compatible) whether a condition holds on the current tree or screenshot.
 * Every non-cancellation failure, including image preparation, falls back to the inspector by returning null; the API
 * key only ever leaves in the `Authorization` header, and neither it nor image data appears in warnings, traces or exceptions.
 * Screenshot requests use TypeSafe's experimental image contract (research/visual-precheck-qualification/README.md).
 */
class SystemOnePrecheck(
  private val config: ResolvedPrecheck,
  private val stats: PrecheckStats,
  private val trace: ModelTraceSink? = null,
  private val timer: PhaseTimer = NoOpPhaseTimer,
  private val warn: (String) -> Unit = { System.err.println(it) },
  private val send: suspend (HttpRequest) -> HttpResponse<String> = defaultSend(config),
) : ConditionPrecheck {
  private val uri = URI("${config.baseUrl}/v1/systemone")
  private val host = uri.host + if (uri.port != -1) ":${uri.port}" else ""
  private val ids = AtomicLong()
  private val warned = AtomicBoolean()

  private class Failure(val kind: String) : Exception(kind)

  override suspend fun check(state: PrecheckState, condition: String, acceptFail: Boolean): PrecheckVerdict? {
    val id = "P${ids.incrementAndGet()}"
    // A fresh reference per image, used exactly once: TypeSafe adds a copy of the image for every textual use.
    val reference = state.screenshot?.let { UUID.randomUUID().toString() }
    val request = SystemOneRequest(
      config.model,
      SystemOneState(state.hierarchy, reference, state.referenceText.ifEmpty { null }),
      mapOf("condition" to NoulQuestion("noul", instructions(state.platform, condition, screenshot = reference != null), NOUL_CRITERIA)),
    )
    trace("$id start prompt=precheck model=${config.model} host=$host", "$id question $condition", "$id state ${Json.encodeToString(SystemOneState.serializer(), request.state)}")
    val started = TimeSource.Monotonic.markNow()
    fun elapsed() = "duration=${started.elapsedNow().inWholeMilliseconds}ms"
    try {
      val (probability, model) = timer.phase(PhaseKind.MODEL_REQUEST, "precheck") {
        // The assets sit outside the traced state, so image data never reaches the trace.
        val assets = state.screenshot?.let { mapOf(checkNotNull(reference) to prepareImage(it)) }
        query(if (assets == null) request else request.copy(assets = assets))
      }
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

  private suspend fun query(request: SystemOneRequest): Pair<Double, String> {
    val body = Json.encodeToString(SystemOneRequest.serializer(), request)
    val response = try {
      // Built inside the guard: the JDK rejects a malformed header value with a message that would quote the key.
      val httpRequest = HttpRequest.newBuilder(uri)
        .timeout(config.timeout.toJavaDuration())
        .header("Content-Type", "application/json")
        .apply { if (request.assets != null) header(IMAGES_HEADER, "true") }
        .apply { config.apiKey?.let { header("Authorization", "Bearer $it") } }
        .POST(HttpRequest.BodyPublishers.ofString(body))
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

  /** Scales [path] to TypeSafe's image limits and encodes it as base64 PNG, in memory; any failure is [Failure] `image`. */
  private suspend fun prepareImage(path: Path): ImageAsset = try {
    withContext(Dispatchers.IO) {
      val source = ImageIO.read(path.toFile()) ?: throw Failure("image")
      val scale = min(1.0, MAX_IMAGE_SIDE.toDouble() / max(source.width, source.height))
      val width = (source.width * scale).roundToInt()
      val height = (source.height * scale).roundToInt()
      // Never upscale or crop: an image too thin to scale within the limits is not sent.
      if (min(width, height) < MIN_IMAGE_SIDE) throw Failure("image")
      val image = if (scale == 1.0) {
        source
      } else {
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { scaled ->
          val graphics = scaled.createGraphics()
          try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(source, 0, 0, width, height, null)
          } finally {
            graphics.dispose()
          }
        }
      }
      val png = ByteArrayOutputStream().also { if (!ImageIO.write(image, "png", it)) throw Failure("image") }
      // TypeSafe's per-image limit; a 1568 px PNG stays well under it, so this only guards a changed scaling rule.
      if (png.size() > MAX_IMAGE_BYTES) throw Failure("image")
      ImageAsset("image", "png", Base64.getEncoder().encodeToString(png.toByteArray()))
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

  /** The default [Json] omits null defaults, so a tree request has no `assets` key. */
  @Serializable
  private data class SystemOneRequest(
    val model: String,
    val state: SystemOneState,
    val questions: Map<String, NoulQuestion>,
    val assets: Map<String, ImageAsset>? = null,
  )

  /** Exactly one of the tree and the screenshot reference is set; the default [Json] omits the other and a null [earlierObservations]. */
  @Serializable
  private class SystemOneState(
    @SerialName("accessibility_tree") val accessibilityTree: String? = null,
    val screenshot: String? = null,
    @SerialName("earlier_observations") val earlierObservations: String? = null,
  )

  @Serializable
  private class ImageAsset(val type: String, val format: String, @SerialName("data_base64") val dataBase64: String)

  @Serializable
  private class NoulQuestion(val type: String, val instructions: String, val criteria: NoulCriteria)

  @Serializable
  private class NoulCriteria(@SerialName("true") val yes: String, @SerialName("false") val no: String)

  @Serializable
  private class SystemOneResponse(val model: String? = null, val answers: Map<String, NoulAnswer> = emptyMap())

  @Serializable
  private class NoulAnswer(val noul: JsonPrimitive? = null)

  private companion object {
    val lenientJson = Json { ignoreUnknownKeys = true }

    // Jev reads instructions literally, so the request frames the question and its evidence (issue #204 A/B: research/precheck-request-shape).
    val NOUL_CRITERIA = NoulCriteria(yes = "The current screen satisfies the condition.", no = "The current screen does not satisfy the condition.")

    const val IMAGES_HEADER = "TypeSafe-Danger-Zone-Images"
    const val MIN_IMAGE_SIDE = 280
    const val MAX_IMAGE_SIDE = 1568
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024

    fun instructions(platform: Platform, condition: String, screenshot: Boolean): String {
      val app = when (platform) {
        Platform.ANDROID_TV -> "Android TV"
        Platform.ANDROID_MOBILE -> "Android"
        Platform.IOS -> "iOS"
      }
      val evidence = if (screenshot) "screenshot" else "accessibility tree"
      return "Using the current $evidence of an $app app, does the screen satisfy this condition? " +
        "Earlier observations are reference only, not proof. Condition: $condition"
    }

    // Redirects stay off so the bearer key is never forwarded to another host.
    fun defaultSend(config: ResolvedPrecheck): suspend (HttpRequest) -> HttpResponse<String> {
      val client = HttpClient.newBuilder().connectTimeout(config.timeout.toJavaDuration()).followRedirects(HttpClient.Redirect.NEVER).build()
      return { request -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await() }
    }
  }
}
