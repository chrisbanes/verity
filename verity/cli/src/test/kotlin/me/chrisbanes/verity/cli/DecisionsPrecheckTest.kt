package me.chrisbanes.verity.cli

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chrisbanes.verity.agent.PrecheckState
import me.chrisbanes.verity.agent.PrecheckVerdict
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.PrecheckSummaryArtifact
import me.chrisbanes.verity.core.timing.RecordingPhaseTimer

private fun ok(probability: String, model: String = "gpt-6-luna-2026") = """{"model":"$model","answers":[{"type":"predicate","name":"condition","probability":$probability}]}"""

private fun ok(probability: Double) = ok(probability.toString())

private val T = PrecheckState(Platform.ANDROID_TV, "S")

class DecisionsPrecheckTest {
  private class Fixture(val server: HttpServer) {
    val requests = CopyOnWriteArrayList<Triple<String, String?, String>>()
    val release = CountDownLatch(1)

    @Volatile var respond: (HttpExchange) -> Pair<Int, String> = { 200 to ok(0.97) }
    val port get() = server.address.port
  }

  private fun withServer(block: suspend (Fixture) -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      val fixture = Fixture(server)
      server.createContext("/") { exchange ->
        fixture.requests += Triple(
          "${exchange.requestMethod} ${exchange.requestURI.path} ${exchange.requestHeaders.getFirst("Content-Type")}",
          exchange.requestHeaders.getFirst("Authorization"),
          exchange.requestBody.readBytes().decodeToString(),
        )
        val (status, body) = fixture.respond(exchange)
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
      }
      server.start()
      try {
        block(fixture)
      } finally {
        fixture.release.countDown()
        server.stop(0)
      }
    }
  }

  private fun config(f: Fixture, apiKey: String? = "s3cr3t") = ResolvedPrecheck("http://127.0.0.1:${f.port}", "gpt-6-luna", apiKey, 500.milliseconds, 0.95, 0.05, provider = PrecheckProvider.DECISIONS, confirmPass = false)

  private fun png(dir: Path, width: Int, height: Int): Path = dir.resolve("${width}x$height.png").also {
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it.toFile())
  }

  private suspend fun <T> withTempDir(block: suspend (Path) -> T): T {
    val dir = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-decisions") }
    try {
      return block(dir)
    } finally {
      withContext(Dispatchers.IO) { dir.toFile().deleteRecursively() }
    }
  }

  private fun JsonObject.instructions() = getValue("questions").jsonArray.single().jsonObject.getValue("instructions").jsonPrimitive.content

  @Test
  fun `a tree check posts one predicate with separate tree and earlier-observation parts`() = withServer { f ->
    val stats = PrecheckStats()
    val client = DecisionsPrecheck(config(f), stats)

    val verdict = client.check(PrecheckState(Platform.IOS, "TREE", referenceText = "R"), "the row is ready", acceptFail = true)
    client.check(PrecheckState(Platform.ANDROID_MOBILE, "TREE"), "the row is ready", acceptFail = true)

    assertThat(verdict).isEqualTo(PrecheckVerdict(true, 0.97, "gpt-6-luna-2026", "127.0.0.1:${f.port}", traceId = "P1"))
    assertThat(stats.accepted).isEqualTo(2)
    val (line, auth, body) = f.requests.first()
    assertThat(line).isEqualTo("POST /v1/decisions application/json")
    assertThat(auth).isEqualTo("Bearer s3cr3t")
    assertThat(Json.parseToJsonElement(body)).isEqualTo(
      Json.parseToJsonElement(
        """{"model":"gpt-6-luna","input":[{"role":"user","content":[
          {"type":"input_text","text":"Current accessibility tree:\nTREE"},
          {"type":"input_text","text":"Earlier observations (reference only, not proof of the current state):\nR"}]}],
          "questions":[{"type":"predicate","name":"condition","instructions":"Using the current accessibility tree of an iOS app, what is the probability that this condition currently holds? Earlier observations are reference only, not proof. Condition: the row is ready"}]}""",
      ),
    )
    val second = Json.parseToJsonElement(f.requests.last().third).jsonObject
    assertThat(second.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray).hasSize(1)
    assertThat(second.instructions()).contains("of an Android app,")
  }

  @Test
  fun `a pass to be confirmed is not accepted, and the inspector's answer is counted and traced`() = withServer { f ->
    val stats = PrecheckStats()
    val lines = CopyOnWriteArrayList<String>()
    val client = DecisionsPrecheck(config(f).copy(confirmPass = true), stats, trace = { _, text -> lines += text })

    val first = client.check(T, "C", acceptFail = true)!!
    val second = client.check(T, "C", acceptFail = true)!!

    assertThat(client.confirmsPass).isTrue()
    assertThat(first.traceId).isEqualTo("P1")
    assertThat(stats.accepted).isEqualTo(0)
    client.confirmation(first, confirmed = true)
    client.confirmation(second, confirmed = false)
    assertThat(stats.confirmed).isEqualTo(1)
    assertThat(stats.overruled).isEqualTo(1)
    assertThat(lines.filter { "confirmation" in it }).isEqualTo(listOf("P1 confirmation outcome=confirmed", "P2 confirmation outcome=overruled"))
    assertThat(stats.render()).isEqualTo("Pre-check: accepted 0, confirmed 1, overruled 1, uncertain fallbacks 0, error fallbacks 0")
  }

  @Test
  fun `with confirm-pass off a pass counts accepted, and confident fails still do with it on`() = withServer { f ->
    val off = PrecheckStats()
    val client = DecisionsPrecheck(config(f), off)
    client.check(T, "C", acceptFail = true)
    assertThat(client.confirmsPass).isFalse()
    assertThat(off.accepted).isEqualTo(1)

    f.respond = { 200 to ok(0.02) }
    val on = PrecheckStats()
    DecisionsPrecheck(config(f).copy(confirmPass = true), on).check(T, "C", acceptFail = true)
    assertThat(on.accepted).isEqualTo(1)
    assertThat(on.confirmed + on.overruled).isEqualTo(0)
  }

  @Test
  fun `a screenshot check sends the original unscaled png once, with no references, and keeps it out of the trace`() = withServer { f ->
    withTempDir { dir ->
      val lines = CopyOnWriteArrayList<String>()
      val timer = RecordingPhaseTimer()
      val client = DecisionsPrecheck(config(f), PrecheckStats(), trace = { _, text -> lines += text }, timer = timer)
      // Wider than System One would accept: Decisions gets the file as is.
      val file = png(dir, 2000, 100)

      val verdict = client.check(PrecheckState(Platform.ANDROID_TV, null, "R", screenshot = file), "the spinner is gone", acceptFail = true)

      assertThat(verdict?.passed).isEqualTo(true)
      val (_, _, body) = f.requests.single()
      val json = Json.parseToJsonElement(body).jsonObject
      assertThat(json.keys).isEqualTo(setOf("model", "input", "questions"))
      val parts = json.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
      assertThat(parts.map { it.getValue("type").jsonPrimitive.content }).isEqualTo(listOf("input_image", "input_text"))
      val url = "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file))
      assertThat(parts[0].getValue("image_url").jsonPrimitive.content).isEqualTo(url)
      assertThat(parts[1].getValue("text").jsonPrimitive.content).isEqualTo("Earlier observations (reference only, not proof of the current state):\nR")
      assertThat(json.instructions()).contains("current screenshot of an Android TV app")
      assertThat(lines.none { url.takeLast(64) in it || "base64" in it || "s3cr3t" in it }).isTrue()
      assertThat(timer.report().render()).contains("model_request precheck x1")
    }
  }

  @Test
  fun `an unreadable or non-png screenshot is an error fallback that sends nothing`() = withServer { f ->
    withTempDir { dir ->
      val warnings = mutableListOf<String>()
      val stats = PrecheckStats()
      val client = DecisionsPrecheck(config(f), stats, warn = { warnings += it })
      val notImage = dir.resolve("not-image.png").also { withContext(Dispatchers.IO) { Files.writeString(it, "NOT-AN-IMAGE") } }

      for (path in listOf(notImage, dir.resolve("missing.png"))) {
        assertThat(client.check(PrecheckState(Platform.ANDROID_TV, null, screenshot = path), "C", acceptFail = true)).isNull()
      }

      assertThat(f.requests).hasSize(0)
      assertThat(stats.errorFallbacks).isEqualTo(2)
      assertThat(warnings.single()).all {
        contains("(image)")
        doesNotContain("NOT-AN-IMAGE")
        doesNotContain(dir.toString())
      }
    }
  }

  @Test
  fun `a confident fail returns a failed verdict`() = withServer { f ->
    f.respond = { 200 to ok(0.02) }
    assertThat(DecisionsPrecheck(config(f), PrecheckStats()).check(T, "C", acceptFail = true)).isEqualTo(PrecheckVerdict(false, 0.02, "gpt-6-luna-2026", "127.0.0.1:${f.port}", traceId = "P1"))
  }

  @Test
  fun `a model missing from the response falls back to the configured one`() = withServer { f ->
    f.respond = { 200 to """{"answers":[{"type":"predicate","name":"condition","probability":0.99}]}""" }
    assertThat(DecisionsPrecheck(config(f), PrecheckStats()).check(T, "C", acceptFail = true)?.model).isEqualTo("gpt-6-luna")
  }

  @Test
  fun `a confident fail the caller cannot use and an uncertain probability are uncertain fallbacks without a warning`() = withServer { f ->
    val warnings = mutableListOf<String>()
    val lines = CopyOnWriteArrayList<String>()
    val stats = PrecheckStats()
    val client = DecisionsPrecheck(config(f), stats, trace = { _, text -> lines += text }, warn = { warnings += it })

    f.respond = { 200 to ok(0.02) }
    assertThat(client.check(T, "C", acceptFail = false)).isNull()
    f.respond = { 200 to ok(0.5) }
    assertThat(client.check(T, "C", acceptFail = true)).isNull()

    assertThat(stats.accepted).isEqualTo(0)
    assertThat(stats.uncertainFallbacks).isEqualTo(2)
    assertThat(stats.errorFallbacks).isEqualTo(0)
    assertThat(warnings).hasSize(0)
    assertThat(lines.filter { "response" in it }.map { it.substringAfter("outcome=") }).isEqualTo(listOf("failed-discarded", "uncertain"))
  }

  @Test
  fun `a refusal is an uncertain fallback traced as refused`() = withServer { f ->
    f.respond = { 200 to """{"model":"gpt-6-luna","answers":[{"type":"refusal","name":"condition","reason":"no"}]}""" }
    val warnings = mutableListOf<String>()
    val lines = CopyOnWriteArrayList<String>()
    val stats = PrecheckStats()

    assertThat(DecisionsPrecheck(config(f), stats, trace = { _, text -> lines += text }, warn = { warnings += it }).check(T, "C", acceptFail = true)).isNull()

    assertThat(stats.uncertainFallbacks).isEqualTo(1)
    assertThat(stats.errorFallbacks).isEqualTo(0)
    assertThat(warnings).hasSize(0)
    assertThat(lines.single { "response" in it }).contains("outcome=refused")
  }

  @Test
  fun `every failure kind falls back, is counted as an error, and warns once without secrets`() = withServer { f ->
    val warnings = mutableListOf<String>()
    val lines = CopyOnWriteArrayList<String>()
    val stats = PrecheckStats()
    val client = DecisionsPrecheck(config(f), stats, trace = { _, text -> lines += text.takeIf { "TREE-TEXT" !in it }.orEmpty() }, warn = { warnings += it })
    val responses = listOf(
      500 to "boom",
      401 to "nope",
      200 to """{"model":"m","answers":[]}""",
      200 to """{"model":"m","answers":[{"type":"predicate","name":"other","probability":0.99}]}""",
      200 to """{"model":"m","answers":[{"type":"choice","name":"condition","probability":0.99}]}""",
      200 to """{"model":"m","answers":[{"type":"predicate","name":"condition"}]}""",
      200 to ok("\"high\""),
      200 to ok(1.5),
      200 to ok(-0.1),
      200 to "not json",
    )
    responses.forEach { response ->
      f.respond = { response }
      assertThat(client.check(PrecheckState(Platform.ANDROID_TV, "TREE-TEXT"), "C", acceptFail = true)).isNull()
    }

    assertThat(stats.errorFallbacks).isEqualTo(responses.size)
    assertThat(stats.uncertainFallbacks).isEqualTo(0)
    assertThat(warnings).hasSize(1)
    assertThat(warnings.single()).all {
      contains("127.0.0.1:${f.port}")
      contains("HTTP 500")
      doesNotContain("s3cr3t")
      doesNotContain("TREE-TEXT")
    }
    assertThat(lines.none { "s3cr3t" in it }).isTrue()
  }

  @Test
  fun `a slow server times out into an error fallback`() = withServer { f ->
    f.respond = {
      f.release.await(5, TimeUnit.SECONDS)
      200 to ok(0.97)
    }
    val warnings = mutableListOf<String>()
    val stats = PrecheckStats()

    assertThat(DecisionsPrecheck(config(f), stats, warn = { warnings += it }).check(T, "C", acceptFail = true)).isNull()

    assertThat(stats.errorFallbacks).isEqualTo(1)
    assertThat(warnings.single()).contains("(timeout)")
  }

  @Test
  fun `the bearer key is sent only when configured, and a header-invalid key falls back without leaking`() = withServer { f ->
    val warnings = mutableListOf<String>()
    DecisionsPrecheck(config(f, apiKey = "k1"), PrecheckStats()).check(T, "C", acceptFail = true)
    DecisionsPrecheck(config(f, apiKey = null), PrecheckStats()).check(T, "C", acceptFail = true)
    val stats = PrecheckStats()
    assertThat(DecisionsPrecheck(config(f, apiKey = "bad\rkey"), stats, warn = { warnings += it }).check(T, "C", acceptFail = true)).isNull()

    assertThat(f.requests.map { it.second }).isEqualTo(listOf("Bearer k1", null))
    assertThat(stats.errorFallbacks).isEqualTo(1)
    assertThat(warnings.single()).doesNotContain("bad")
  }

  @Test
  fun `a redirect is never followed, so the key stays on the configured host`() = withServer { f ->
    f.respond = { exchange ->
      exchange.responseHeaders.add("Location", "/other")
      302 to ""
    }
    val warnings = mutableListOf<String>()
    val stats = PrecheckStats()

    assertThat(DecisionsPrecheck(config(f), stats, warn = { warnings += it }).check(T, "C", acceptFail = true)).isNull()

    assertThat(f.requests.map { it.first.substringBefore(" application") }).isEqualTo(listOf("POST /v1/decisions"))
    assertThat(stats.errorFallbacks).isEqualTo(1)
    assertThat(warnings.single()).contains("HTTP 302")
  }

  @Test
  fun `trace and timing cover successes and failures without the key`() = withServer { f ->
    val lines = CopyOnWriteArrayList<String>()
    val timer = RecordingPhaseTimer()
    val client = DecisionsPrecheck(config(f), PrecheckStats(), trace = { _, text -> lines += text }, timer = timer)

    client.check(PrecheckState(Platform.ANDROID_TV, "S", referenceText = "R"), "C", acceptFail = true)
    f.respond = { 500 to "boom" }
    client.check(T, "C", acceptFail = true)

    assertThat(lines[0]).isEqualTo("P1 start prompt=precheck model=gpt-6-luna host=127.0.0.1:${f.port}")
    assertThat(lines[1]).isEqualTo("P1 question C")
    assertThat(lines[2]).isEqualTo("P1 input Current accessibility tree: S")
    assertThat(lines[4]).all {
      contains("P1 response duration=")
      contains("model=gpt-6-luna-2026 predicate=0.97 outcome=passed")
    }
    assertThat(lines.single { "P2 failure" in it }).contains("HTTP 500")
    assertThat(lines.none { "s3cr3t" in it }).isTrue()
    assertThat(timer.report().render()).contains("model_request precheck x2")
  }

  @Test
  fun `cancelling the caller propagates and counts nothing`() = withServer { f ->
    val entered = CountDownLatch(1)
    f.respond = {
      entered.countDown()
      f.release.await(5, TimeUnit.SECONDS)
      200 to ok(0.97)
    }
    val stats = PrecheckStats()
    val client = DecisionsPrecheck(config(f).copy(timeout = 10_000.milliseconds), stats)
    coroutineScope {
      val call = async { client.check(T, "C", acceptFail = true) }
      withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) }
      call.cancelAndJoin()
      assertThat(runCatching { call.await() }.exceptionOrNull()).isNotNull().isInstanceOf<CancellationException>()
    }
    assertThat(stats.snapshot().let { listOf(it.accepted, it.uncertainFallbacks, it.errorFallbacks) }).isEqualTo(listOf(0, 0, 0))
  }

  @Test
  fun `an enclosing deadline bounds a request and counts nothing`() = withServer { f ->
    f.respond = {
      f.release.await(5, TimeUnit.SECONDS)
      200 to ok(0.97)
    }
    val stats = PrecheckStats()
    val client = DecisionsPrecheck(config(f).copy(timeout = 10_000.milliseconds), stats)

    val failure = runCatching { withTimeout(100.milliseconds) { client.check(T, "C", acceptFail = true) } }.exceptionOrNull()

    assertThat(failure).isNotNull().isInstanceOf<TimeoutCancellationException>()
    assertThat(stats.snapshot().let { listOf(it.accepted, it.uncertainFallbacks, it.errorFallbacks) }).isEqualTo(listOf(0, 0, 0))
  }
}
