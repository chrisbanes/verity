package me.chrisbanes.verity.cli

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chrisbanes.verity.agent.PrecheckVerdict
import me.chrisbanes.verity.core.timing.RecordingPhaseTimer

private fun ok(p: String, model: String = "jev-1.13.0") = """{"model":"$model","answers":{"condition":{"type":"noul","noul":$p}}}"""

private fun ok(p: Double) = ok(p.toString())

class SystemOnePrecheckTest {
  private class Fixture(val server: HttpServer) {
    val requests = CopyOnWriteArrayList<Triple<String, String?, String>>()
    val release = CountDownLatch(1)

    @Volatile var respond: (HttpExchange) -> Pair<Int, String> = { 200 to ok(0.95) }
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

  private fun config(f: Fixture, apiKey: String? = "s3cr3t") = ResolvedPrecheck("http://127.0.0.1:${f.port}", "jev-latest", apiKey, 500.milliseconds, 0.9, 0.1)

  @Test
  fun `confident pass posts the system one request and counts it accepted`() = withServer { f ->
    val client = SystemOnePrecheck(config(f))

    val verdict = client.check("S", "C", acceptFail = true)

    assertThat(verdict).isEqualTo(PrecheckVerdict(true, 0.95, "jev-1.13.0", "127.0.0.1:${f.port}"))
    val (line, _, body) = f.requests.single()
    assertThat(line).isEqualTo("POST /v1/systemone application/json")
    assertThat(Json.parseToJsonElement(body)).isEqualTo(
      Json.parseToJsonElement("""{"model":"jev-latest","state":"S","questions":{"condition":{"type":"noul","instructions":"C"}}}"""),
    )
    assertThat(client.stats.accepted).isEqualTo(1)
  }

  @Test
  fun `confident fail returns a failed verdict`() = withServer { f ->
    f.respond = { 200 to ok(0.05) }
    assertThat(SystemOnePrecheck(config(f)).check("S", "C", acceptFail = true)!!.passed).isEqualTo(false)
  }

  @Test
  fun `a confident fail the caller cannot use counts as an uncertain fallback`() = withServer { f ->
    f.respond = { 200 to ok(0.05) }
    val client = SystemOnePrecheck(config(f))

    assertThat(client.check("S", "C", acceptFail = false)).isNull()
    assertThat(client.stats.accepted).isEqualTo(0)
    assertThat(client.stats.uncertainFallbacks).isEqualTo(1)
  }

  @Test
  fun `uncertain probability falls back without a warning`() = withServer { f ->
    f.respond = { 200 to ok(0.5) }
    val warnings = mutableListOf<String>()
    val client = SystemOnePrecheck(config(f), warn = { warnings += it })

    assertThat(client.check("S", "C", acceptFail = true)).isNull()
    assertThat(client.stats.uncertainFallbacks).isEqualTo(1)
    assertThat(client.stats.errorFallbacks).isEqualTo(0)
    assertThat(warnings).hasSize(0)
  }

  @Test
  fun `every failure kind falls back, is counted as an error, and warns once without secrets`() = withServer { f ->
    val warnings = mutableListOf<String>()
    val client = SystemOnePrecheck(config(f), warn = { warnings += it })
    val responses = listOf(
      500 to "boom",
      401 to "nope",
      200 to """{"model":"m","answers":{}}""",
      200 to ok("\"high\""),
      200 to ok(1.5),
      200 to "not json",
    )
    responses.forEach { response ->
      f.respond = { response }
      assertThat(client.check("TREE-TEXT", "C", acceptFail = true)).isNull()
    }

    assertThat(client.stats.errorFallbacks).isEqualTo(responses.size)
    assertThat(client.stats.uncertainFallbacks).isEqualTo(0)
    assertThat(warnings).hasSize(1)
    assertThat(warnings.single()).all {
      contains("127.0.0.1:${f.port}")
      contains("HTTP 500")
      doesNotContain("s3cr3t")
      doesNotContain("TREE-TEXT")
    }
  }

  @Test
  fun `a key the JDK rejects as a header falls back as an error without leaking it`() = withServer { f ->
    val warnings = mutableListOf<String>()
    val client = SystemOnePrecheck(config(f, apiKey = "bad\rkey"), warn = { warnings += it })

    assertThat(client.check("S", "C", acceptFail = true)).isNull()

    assertThat(client.stats.errorFallbacks).isEqualTo(1)
    assertThat(warnings.single()).doesNotContain("bad")
    assertThat(f.requests).hasSize(0)
  }

  @Test
  fun `a slow server times out into an error fallback`() = withServer { f ->
    f.respond = {
      f.release.await(5, TimeUnit.SECONDS)
      200 to ok(0.95)
    }
    val warnings = mutableListOf<String>()
    val client = SystemOnePrecheck(config(f), warn = { warnings += it })

    val started = TimeSource.Monotonic.markNow()
    assertThat(client.check("S", "C", acceptFail = true)).isNull()

    assertThat(started.elapsedNow().inWholeMilliseconds).isLessThan(1500)
    assertThat(client.stats.errorFallbacks).isEqualTo(1)
    assertThat(warnings.single()).contains("(timeout)")
  }

  @Test
  fun `bearer key is sent only when configured`() = withServer { f ->
    SystemOnePrecheck(config(f, apiKey = "k1")).check("S", "C", acceptFail = true)
    SystemOnePrecheck(config(f, apiKey = null)).check("S", "C", acceptFail = true)

    assertThat(f.requests.map { it.second }).isEqualTo(listOf("Bearer k1", null))
  }

  @Test
  fun `trace and timing cover successes and failures without the key`() = withServer { f ->
    val lines = CopyOnWriteArrayList<String>()
    val timer = RecordingPhaseTimer()
    val client = SystemOnePrecheck(config(f), trace = { _, text -> lines += text }, timer = timer)

    client.check("S", "C", acceptFail = true)
    f.respond = { 500 to "boom" }
    client.check("S", "C", acceptFail = true)

    assertThat(lines.size).isEqualTo(8)
    assertThat(lines[0]).isEqualTo("P1 start prompt=precheck model=jev-latest host=127.0.0.1:${f.port}")
    assertThat(lines[1]).isEqualTo("P1 question C")
    assertThat(lines[2]).isEqualTo("P1 state S")
    assertThat(lines[3]).all {
      contains("P1 response duration=")
      contains("model=jev-1.13.0 noul=0.95 outcome=passed")
    }
    assertThat(lines[7]).all {
      contains("P2 failure duration=")
      contains("HTTP 500")
    }
    assertThat(lines.none { "s3cr3t" in it }).isTrue()
    assertThat(timer.report().render()).contains("model_request precheck x2")
  }

  @Test
  fun `cancelling the caller propagates and counts nothing`() = withServer { f ->
    val entered = CountDownLatch(1)
    f.respond = {
      entered.countDown()
      f.release.await(5, TimeUnit.SECONDS)
      200 to ok(0.95)
    }
    val client = SystemOnePrecheck(config(f).copy(timeout = 10_000.milliseconds))
    coroutineScope {
      val call = async { client.check("S", "C", acceptFail = true) }
      withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) }
      call.cancelAndJoin()
      assertThat(runCatching { call.await() }.exceptionOrNull()).isNotNull().isInstanceOf<CancellationException>()
    }
    assertThat(client.stats.snapshot().let { listOf(it.accepted, it.uncertainFallbacks, it.errorFallbacks) }).isEqualTo(listOf(0, 0, 0))
  }
}
