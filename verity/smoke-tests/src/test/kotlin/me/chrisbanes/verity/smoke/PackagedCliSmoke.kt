package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.Orchestrator
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.containsText
import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession
import org.junit.jupiter.api.Tag

/** Runs production exclusively from java -jar or fat-JAR + fixture-JAR child classpaths. */
@Tag("packaged-android")
class PackagedCliSmoke {
  @Test
  fun `universal Android runtime supports factory CLI and both MCP transports`() = runBlocking {
    check(System.getProperty("os.name").lowercase().contains("linux"))
    check(System.getProperty("os.arch") in listOf("amd64", "x86_64"))
    val serial = checkNotNull(System.getenv("VERITY_PACKAGED_ANDROID_SERIAL"))
    check(serial.startsWith("emulator-"))
    val jar = File(System.getProperty("verity.packaged.jar"))
    val probe = File(System.getProperty("verity.packaged.probe"))
    val directory = File(System.getProperty("verity.packaged.receipts"))
    withContext(Dispatchers.IO) { directory.mkdirs() }
    val receipt = File(directory, "universal-android.txt")
    fun record(text: String) {
      receipt.appendText(text + "\n")
    }
    withContext(Dispatchers.IO) {
      receipt.writeText("host=${System.getProperty("os.name")}/${System.getProperty("os.arch")}\nhead=${System.getenv("GITHUB_SHA")}\nrun=${System.getenv("GITHUB_RUN_ID")}\nserial=$serial\njar=${jar.name}\nsha256=${sha256(jar)}\nprobe_sha256=${sha256(probe)}\n")
    }
    val requests = AtomicInteger()
    val trap = withContext(Dispatchers.IO) {
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
          requests.incrementAndGet()
          exchange.sendResponseHeaders(500, -1)
          exchange.close()
        }
        start()
      }
    }
    val endpoint = "http://127.0.0.1:${trap.address.port}"
    val owned = mutableListOf<PackagedChild>()
    try {
      val factory = start(
        directory,
        "factory",
        listOf("-cp", "${jar.absolutePath}${File.pathSeparator}${probe.absolutePath}", "me.chrisbanes.verity.smoke.PackagedRuntimeProbe", "android", serial),
      ).also { owned += it }
      // Only job-owned bootstrap is allowed here. Capture timing begins after connect.
      withTimeout(600_000) {
        while (!withContext(Dispatchers.IO) { factory.stdout.readText().contains("PACKAGED_FACTORY_CONNECTED") }) {
          check(factory.process.isAlive) { "Packaged factory failed before connect: ${factory.stderr.readText()}" }
          delay(20)
        }
      }
      withTimeout(120_000) {
        awaitSuccess(factory)
        val proof = withContext(Dispatchers.IO) { factory.stdout.readText() }
        check(proof.contains("PACKAGED_FACTORY_CAPTURE_BOUNDED_BACK_CALLER_JOIN_REUSE_OK"))
        check(proof.contains("PACKAGED_FACTORY_CLOSE_COMPLETED"))
        withContext(Dispatchers.IO) { record("factory_operations=passed close=completed") }
        val fixtures = File(directory, "journeys")
        val output = File(directory, "results")
        withContext(Dispatchers.IO) {
          fixtures.mkdirs()
          File(fixtures, "settings.journey.yaml").writeText(PACKAGED_ANDROID_JOURNEY)
        }
        val options = listOf("-jar", jar.absolutePath, "--provider", "ollama", "--api-key", endpoint, "--device", serial, "--output-path", output.path)
        val listing = start(directory, "list", options + listOf("list", "--path", fixtures.path)).also { owned += it }
        awaitSuccess(listing)
        check(withContext(Dispatchers.IO) { listing.stdout.readText().contains("Packaged Settings visible") })
        val running = start(directory, "run", options + listOf("run", fixtures.path)).also { owned += it }
        awaitSuccess(running)
        val summaries = withContext(Dispatchers.IO) { output.walkTopDown().filter { it.name == "summary.json" }.toList() }
        check(summaries.size == 1) { "Expected one persisted suite summary" }
        val summary = withContext(Dispatchers.IO) { Json.parseToJsonElement(summaries.single().readText()).jsonObject }
        check(summary["status"]!!.jsonPrimitive.content == "passed") { "Suite did not pass: $summary" }
        check(withContext(Dispatchers.IO) { output.walkTopDown().any { it.name.endsWith(".json") && it.name != "summary.json" } })
        withContext(Dispatchers.IO) { record("cli_list_run=persisted-pass") }
        stdio(directory, options, serial, owned)
        withContext(Dispatchers.IO) { record("mcp_stdio=initialize tools-list open capture key close post-close-rejection; stdout=protocol-only") }
        http(directory, options, serial, owned)
        withContext(Dispatchers.IO) { record("mcp_http=initialize tools-list open capture key close post-close-rejection") }
        assertThat(requests.get()).isEqualTo(0)
      }
    } finally {
      withContext(NonCancellable + Dispatchers.IO) {
        val cleanupFailures = owned.asReversed().mapNotNull { child -> runCatching { child.stop() }.exceptionOrNull() }
        trap.stop(0)
        record("model_requests=${requests.get()} children_alive=${owned.count { it.process.isAlive }} cleanup=completed")
        assertThat(owned.any { it.process.isAlive }).isFalse()
        assertThat(requests.get()).isEqualTo(0)
        check(cleanupFailures.isEmpty()) { "Owned child cleanup failed: $cleanupFailures" }
      }
    }
  }

  private suspend fun stdio(directory: File, options: List<String>, serial: String, owned: MutableList<PackagedChild>) {
    val child = start(directory, "stdio", options + listOf("mcp"), pipeOutput = true).also { owned += it }
    val captured = ByteArrayOutputStream()
    val input = object : FilterInputStream(child.process.inputStream) {
      override fun read(): Int = super.read().also { if (it >= 0) captured.write(it) }
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
        if (it > 0) captured.write(bytes, offset, it)
      }
    }
    val client = Client(Implementation("verity-packaged-smoke", "1"))
    try {
      client.connect(StdioClientTransport(input.asSource().buffered(), child.process.outputStream.asSink().buffered()))
      tools(client, serial)
    } finally {
      withContext(NonCancellable) {
        try {
          withTimeout(10_000) { client.close() }
        } finally {
          withContext(Dispatchers.IO) {
            child.stop()
            child.stdout.writeBytes(captured.toByteArray())
          }
        }
      }
    }
    val frames = captured.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
    check(frames.isNotEmpty())
    for (line in frames) {
      val frame = Json.parseToJsonElement(line).jsonObject
      check(frame["jsonrpc"]!!.jsonPrimitive.content == "2.0")
      check(frame.containsKey("result") || frame.containsKey("method") || frame.containsKey("error"))
    }
    check(withContext(Dispatchers.IO) { child.stderr.readText().contains("Starting Verity MCP server (stdio)...") })
  }

  private suspend fun http(directory: File, options: List<String>, serial: String, owned: MutableList<PackagedChild>) {
    val port = withContext(Dispatchers.IO) { ServerSocket(0).use { it.localPort } }
    val child = start(directory, "http", options + listOf("mcp", "--transport", "http", "--host", "127.0.0.1", "--port", port.toString())).also { owned += it }
    val http = HttpClient(CIO) { install(SSE) }
    val client = Client(Implementation("verity-packaged-http-smoke", "1"))
    val transport = StreamableHttpClientTransport(http, "http://127.0.0.1:$port/mcp")
    try {
      withTimeout(30_000) {
        while (!withContext(Dispatchers.IO) {
            runCatching {
              Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
              true
            }.getOrDefault(false)
          }
        ) {
          check(child.process.isAlive)
          delay(20)
        }
      }
      client.connect(transport)
      tools(client, serial)
    } finally {
      withContext(NonCancellable) {
        try {
          withTimeout(10_000) { transport.terminateSession() }
        } finally {
          try {
            withTimeout(10_000) { client.close() }
          } finally {
            http.close()
            withContext(Dispatchers.IO) { child.stop() }
          }
        }
      }
    }
    withContext(Dispatchers.IO) {
      check(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) } }.isFailure) { "Owned HTTP port remains open" }
    }
  }

  private suspend fun tools(client: Client, serial: String) {
    assertThat(client.serverVersion!!.name).isEqualTo("verity")
    assertThat(client.listTools().tools.size).isEqualTo(14)
    suspend fun call(name: String, arguments: JsonObject) = client.callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))
    fun text(result: CallToolResult) = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    val opened = call(
      "open_session",
      buildJsonObject {
        put("platform", "android")
        put("device", serial)
        put("disable_animations", false)
      },
    )
    check(opened.isError != true) { text(opened) }
    val id = Regex("session_id: ([a-f0-9-]+)").find(text(opened))?.groupValues?.get(1) ?: error("Missing session ID: ${text(opened)}")
    val args = buildJsonObject { put("session_id", id) }
    try {
      val captured = call("capture_hierarchy", args)
      check(captured.isError != true && text(captured).isNotBlank()) { text(captured) }
      val key = call(
        "press_key",
        buildJsonObject {
          put("session_id", id)
          put("key", "BACK")
        },
      )
      check(key.isError != true) { text(key) }
    } finally {
      withContext(NonCancellable) {
        val closed = withTimeout(10_000) { call("close_session", args) }
        check(closed.isError != true) { text(closed) }
      }
    }
    val postClose = call("capture_hierarchy", args)
    assertThat(postClose.isError == true).isTrue()
  }

  private suspend fun start(directory: File, name: String, args: List<String>, pipeOutput: Boolean = false): PackagedChild = withContext(Dispatchers.IO) {
    val stdout = File(directory, "$name.stdout")
    val stderr = File(directory, "$name.stderr")
    val builder = ProcessBuilder(listOf(File(System.getProperty("java.home"), "bin/java").path, "-Xmx512m") + args)
      .directory(directory).redirectError(stderr)
    builder.environment().remove("CLASSPATH")
    if (!pipeOutput) builder.redirectOutput(stdout)
    PackagedChild(builder.start(), stdout, stderr)
  }

  private suspend fun awaitSuccess(child: PackagedChild) {
    while (child.process.isAlive) delay(20)
    check(child.process.exitValue() == 0) { "Child failed: ${withContext(Dispatchers.IO) { child.stderr.readText() }}" }
  }

  private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

private class PackagedChild(val process: Process, val stdout: File, val stderr: File) {
  fun stop() {
    val descendants = process.descendants().toList()
    process.outputStream.close()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
    descendants.forEach { if (it.isAlive) it.destroy() }
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      check(process.waitFor(5, TimeUnit.SECONDS))
    }
    descendants.forEach { if (it.isAlive) it.destroyForcibly() }
    check(!process.isAlive && descendants.none { it.isAlive }) { "Owned process tree remains alive" }
  }
}

/** Failure and delayed-response cleanup run offline, before CI device qualification. */
class PackagedChildLifecycleTest {
  @Test
  fun `assertion failure while owned child is active still joins cleanup`() = fixture { child ->
    assertFailsWith<IllegalStateException> {
      try {
        check(child.process.isAlive)
        error("Intentional assertion failure")
      } finally {
        withContext(NonCancellable + Dispatchers.IO) { child.stop() }
      }
    }
    assertThat(child.process.isAlive).isFalse()
  }

  @Test
  fun `delayed child response times out and cleanup joins it`() = fixture { child ->
    assertFailsWith<TimeoutCancellationException> {
      try {
        withTimeout(50) {
          while (child.process.isAlive) delay(10)
        }
      } finally {
        withContext(NonCancellable + Dispatchers.IO) { child.stop() }
      }
    }
    assertThat(child.process.isAlive).isFalse()
  }

  private fun fixture(block: suspend (PackagedChild) -> Unit) = runBlocking {
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("packaged-child-cleanup").toFile() }
    var child: PackagedChild? = null
    try {
      child = withContext(Dispatchers.IO) {
        val source = File(directory, "PackagedLifecycleFixture.java")
        source.writeText("public class PackagedLifecycleFixture { public static void main(String[] args) throws Exception { System.out.println(\"entered\"); Thread.sleep(300000); } }")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, source.path) == 0)
        val stdout = File(directory, "stdout")
        val stderr = File(directory, "stderr")
        PackagedChild(ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-Xmx512m", "-cp", directory.path, "PackagedLifecycleFixture").redirectOutput(stdout).redirectError(stderr).start(), stdout, stderr)
      }
      val active = checkNotNull(child)
      withTimeout(10_000) {
        while (!withContext(Dispatchers.IO) { active.stdout.readText().contains("entered") }) {
          check(active.process.isAlive)
          delay(10)
        }
      }
      block(active)
    } finally {
      withContext(NonCancellable + Dispatchers.IO) {
        child?.stop()
        directory.deleteRecursively()
      }
    }
  }
}

// Reuse the non-target action + visible condition from the existing Settings smoke.
// The native action invokes normal animation synchronization; no model search is used.
private val PACKAGED_ANDROID_JOURNEY = """
  name: Packaged Settings visible
  app: com.android.settings
  platform: android
  steps:
    - Scroll down
    - "[?visible] System"
""".trimIndent() + "\n"

/** Offline route sensitivity only: real Settings visibility remains a required CI assertion. */
class PackagedJourneyFixtureTest {
  @Test
  fun `fixture executes native action and animation wait before real visible assertion without models`() = runTest {
    val result = executeFixture(systemVisible = true)
    assertThat(result.passed).isTrue()
  }

  @Test
  fun `fixture fails when target is absent without using a model or inventing a pass`() = runTest {
    val result = executeFixture(systemVisible = false)
    assertThat(result.passed).isFalse()
  }

  private suspend fun executeFixture(systemVisible: Boolean): me.chrisbanes.verity.agent.JourneyResult {
    val fake = FakeDeviceSession(platform = Platform.ANDROID_MOBILE)
    val events = mutableListOf<String>()
    val scroll = Interaction.Scroll(Direction.DOWN)
    val session = object : DeviceSession by fake {
      override suspend fun executeActions(flow: ActionFlow) = fake.executeActions(flow).also {
        events += if (flow.actions.contains(scroll)) "scroll" else "launch"
      }
      override suspend fun waitForAnimationToEnd() {
        events += "animation-wait"
      }
      override suspend fun containsText(text: String, ignoreCase: Boolean) = captureHierarchyTree().containsText(text, ignoreCase)
      override suspend fun captureHierarchyTree(): HierarchyNode {
        events += "capture"
        return HierarchyNode(attributes = if (systemVisible) mapOf("text" to "System") else emptyMap())
      }
    }
    val journey = JourneyLoader.fromYaml(PACKAGED_ANDROID_JOURNEY)
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { _, _ -> error("Fixture must not call navigator model") } },
      inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { _, _, _ -> error("VISIBLE fixture must not call inspector model") },
          evaluateVisualContent = { _, _, _, _ -> error("VISIBLE fixture must not call visual model") },
        )
      },
    )
    val result = orchestrator.run(journey)
    assertThat(events).isEqualTo(listOf("launch", "scroll", "animation-wait", "capture"))
    assertThat(fake.executedActionFlows.flatMap { it.actions }).isEqualTo(listOf(Interaction.LaunchApp(), scroll))
    assertThat(result.segments.single().actions).isEqualTo(listOf("Scroll down"))
    assertThat(result.segments.single().assertionDescription).isEqualTo("System")
    return result
  }
}
