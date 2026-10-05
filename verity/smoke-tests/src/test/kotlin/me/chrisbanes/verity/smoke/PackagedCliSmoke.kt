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
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
@Tag("packaged-android")
class PackagedCliSmoke {
  @org.junit.jupiter.api.TestFactory
  fun androidVariants() = packagedVariants(Platform.ANDROID_MOBILE)
}

@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
@Tag("packaged-ios")
class PackagedIosSmoke {
  @org.junit.jupiter.api.TestFactory
  fun iosVariants() = packagedVariants(Platform.IOS)
}

private val Platform.cliArgument: String get() = name

private val Platform.wireName: String get() = when (this) {
  Platform.ANDROID_MOBILE -> "android"
  Platform.ANDROID_TV -> "android-tv"
  Platform.IOS -> "ios"
}

private fun packagedVariants(platform: Platform): List<org.junit.jupiter.api.DynamicTest> {
  val variants = System.getProperty("verity.packaged.variants").split(',')
  check(variants.isNotEmpty() && variants.distinct().size == variants.size)
  val target = checkNotNull(System.getenv(if (platform == Platform.IOS) "VERITY_PACKAGED_IOS_UDID" else "VERITY_PACKAGED_ANDROID_SERIAL"))
  val probe = File(System.getProperty("verity.packaged.probe"))
  val inputs = variants.map { variant ->
    val jar = File(System.getProperty("verity.packaged.jar.$variant"))
    PackagedInputs(variant, jar, System.getProperty("verity.packaged.sha.$variant"), probe, System.getProperty("verity.packaged.probe.sha"))
      .also { it.validate(System.getProperty("os.name"), System.getProperty("os.arch"), platform) }
  }
  check(System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("GITHUB_JOB") in listOf("smoke-android", "smoke-ios")) { "Native qualification requires configured job-owned CI" }
  if (platform == Platform.IOS) java.util.UUID.fromString(target) else check(target.startsWith("emulator-"))
  val targetReceipt = File(checkNotNull(System.getenv("VERITY_PACKAGED_TARGET_RECEIPT")))
  val binding = Json.parseToJsonElement(targetReceipt.readText()).jsonObject
  check(
    binding["target"]!!.jsonPrimitive.content == target && binding["run"]!!.jsonPrimitive.content == System.getenv("GITHUB_RUN_ID") &&
      binding["job"]!!.jsonPrimitive.content == System.getenv("GITHUB_JOB") && binding["head"]!!.jsonPrimitive.content == System.getenv("GITHUB_SHA") &&
      binding["attempt"]!!.jsonPrimitive.content == System.getenv("GITHUB_RUN_ATTEMPT") && binding["kind"]!!.jsonPrimitive.content == if (platform == Platform.IOS) "ios" else "android",
  ) { "Target is not bound to this CI job/head/attempt" }
  return inputs.map { input ->
    org.junit.jupiter.api.DynamicTest.dynamicTest("${input.variant} $platform packaged runtime") {
      runBlocking { PackagedQualification().qualify(input, platform, target, targetReceipt) }
    }
  }
}

private data class PackagedInputs(val variant: String, val jar: File, val expectedJarHash: String, val probe: File, val expectedProbeHash: String) {
  fun validate(os: String, arch: String, platform: Platform) {
    val mac = os.lowercase().contains("mac") && arch in listOf("aarch64", "arm64")
    val linux = os.lowercase().contains("linux") && arch in listOf("amd64", "x86_64", "x86-64")
    check(mac || linux) { "Unsupported advertised host $os/$arch" }
    check(variant == "universal" || (variant == "macos-aarch64" && mac) || (variant == "linux-x86_64" && linux)) { "Variant $variant does not match $os/$arch" }
    check(platform != Platform.IOS || mac) { "iOS simulator qualification requires macOS ARM64" }
    check(jar.isFile && probe.isFile) { "Missing production or test-only probe artifact" }
    check(packagedSha256(jar) == expectedJarHash && packagedSha256(probe) == expectedProbeHash) { "Artifact hash changed before setup" }
  }
}

private fun packagedSha256(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().buffered().use { input ->
    val buffer = ByteArray(8192)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      digest.update(buffer, 0, count)
    }
  }
  return java.util.HexFormat.of().formatHex(digest.digest())
}

private class PackagedQualification {
  suspend fun qualify(input: PackagedInputs, platform: Platform, target: String, targetReceipt: File) = kotlinx.coroutines.coroutineScope {
    // Recheck immediately before any setup, not only during dynamic-test discovery.
    withContext(Dispatchers.IO) { input.validate(System.getProperty("os.name"), System.getProperty("os.arch"), platform) }
    val jar = input.jar
    val probe = input.probe
    val directory = File(System.getProperty("verity.packaged.receipts"), "${System.getenv("GITHUB_RUN_ID")}-${System.getenv("GITHUB_RUN_ATTEMPT")}/${System.getenv("GITHUB_JOB")}/${input.variant}-${platform.wireName}")
    withContext(Dispatchers.IO) { directory.mkdirs() }
    val receipt = File(directory, "qualification.txt")
    fun record(text: String) {
      receipt.appendText(text + "\n")
    }
    withContext(Dispatchers.IO) {
      receipt.writeText("host=${System.getProperty("os.name")}/${System.getProperty("os.arch")}\ntested_head=${System.getenv("GITHUB_SHA")}\ncandidate_head=${System.getenv("VERITY_PACKAGED_CANDIDATE_HEAD")}\nrun=${System.getenv("GITHUB_RUN_ID")}\nattempt=${System.getenv("GITHUB_RUN_ATTEMPT")}\njob=${System.getenv("GITHUB_JOB")}\ntarget=$target\nplatform=$platform\nvariant=${input.variant}\njar=${jar.name}\nsha256=${input.expectedJarHash}\nprobe_sha256=${input.expectedProbeHash}\n")
      targetReceipt.copyTo(File(directory, "target.json"), overwrite = true)
    }
    val requests = AtomicInteger()
    val trap = withContext(NonCancellable + Dispatchers.IO) {
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
    val owned = java.util.concurrent.CopyOnWriteArrayList<PackagedChild>()
    val observer = launch {
      while (kotlinx.coroutines.currentCoroutineContext().isActive) {
        withContext(Dispatchers.IO) { owned.forEach { it.observeDescendants() } }
        delay(20)
      }
    }
    try {
      if (platform == Platform.IOS) withContext(Dispatchers.IO) { requireDriverPortClosed() }
      val factory = start(directory, owned, "factory", listOf("-cp", "${jar.absolutePath}${File.pathSeparator}${probe.absolutePath}", "me.chrisbanes.verity.smoke.PackagedRuntimeProbe", if (platform == Platform.IOS) "ios" else "android", target))
      val mappings = if (platform == Platform.IOS) listOf(Platform.IOS) else listOf(Platform.ANDROID_MOBILE, Platform.ANDROID_TV)
      for (mapping in mappings) {
        withTimeout(600_000) { marker(factory, "PACKAGED_FACTORY_CONNECTED platform=$mapping") }
        withTimeout(120_000) {
          marker(factory, "PACKAGED_FACTORY_CAPTURE_BOUNDED_KEY_CALLER_JOIN_REUSE_OK platform=$mapping")
          marker(factory, "PACKAGED_FACTORY_CLOSE_COMPLETED platform=$mapping")
        }
      }
      withTimeout(10_000) { awaitSuccess(factory) }
      withContext(Dispatchers.IO) {
        if (platform == Platform.IOS) requireDriverPortClosed()
        record("factory_mappings=$mappings bounded_noarg_key_caller_join_reuse=passed close=completed")
      }
      val fixtures = File(directory, "journeys")
      val output = File(directory, "results")
      withContext(Dispatchers.IO) {
        fixtures.mkdirs()
        File(fixtures, "settings.journey.yaml").writeText(packagedJourney(platform))
      }
      val options = listOf("-jar", jar.absolutePath, "--provider", "ollama", "--api-key", endpoint, "--platform", platform.cliArgument, "--device", target, "--output-path", output.path)
      val help = start(directory, owned, "help", listOf("-jar", jar.absolutePath, "--help"))
      withTimeout(30_000) { awaitSuccess(help) }
      check(withContext(Dispatchers.IO) { help.stdout.readText().contains("Usage:") })
      val listing = start(directory, owned, "list", options + listOf("list", "--path", fixtures.path))
      withTimeout(30_000) { awaitSuccess(listing) }
      check(withContext(Dispatchers.IO) { listing.stdout.readText().contains("Settings visible") })
      val running = start(directory, owned, "run", options + listOf("run", fixtures.path))
      withTimeout(600_000) { awaitSuccess(running) }
      val summaries = withContext(Dispatchers.IO) { output.walkTopDown().filter { it.name == "summary.json" }.toList() }
      check(summaries.size == 1) { "Expected one persisted suite summary" }
      val summary = withContext(Dispatchers.IO) { Json.parseToJsonElement(summaries.single().readText()).jsonObject }
      check(summary["status"]!!.jsonPrimitive.content == "passed" && summary["total"]!!.jsonPrimitive.content == "1" && summary["passed"]!!.jsonPrimitive.content == "1" && summary["failed"]!!.jsonPrimitive.content == "0") { "Suite did not pass: $summary" }
      val journeyRefs = summary["journeys"]!!.jsonArray
      check(journeyRefs.size == 1)
      for (journey in journeyRefs) {
        val reference = journey.jsonObject
        check(reference["status"]!!.jsonPrimitive.content == "passed")
        val resultFile = File(summaries.single().parentFile, reference["path"]!!.jsonPrimitive.content)
        val result = withContext(Dispatchers.IO) { Json.parseToJsonElement(resultFile.readText()).jsonObject }
        check(result["passed"]!!.jsonPrimitive.content == "true") { "Persisted journey result failed: $result" }
      }
      withContext(Dispatchers.IO) {
        if (platform == Platform.IOS) requireDriverPortClosed()
        record("cli_help_list_run=persisted-pass")
      }
      stdio(directory, options, target, platform, owned, ::record)
      withContext(Dispatchers.IO) { record("mcp_stdio=initialize tools-list open capture key diff close snapshot-rejection; stdout=protocol-only") }
      http(directory, options, target, platform, owned, ::record)
      withContext(Dispatchers.IO) { record("mcp_http=initialize tools-list open capture key diff close snapshot-rejection") }
      assertThat(requests.get()).isEqualTo(0)
    } finally {
      withContext(NonCancellable) { observer.cancelAndJoin() }
      withContext(NonCancellable + Dispatchers.IO) {
        val cleanupFailures = owned.asReversed().mapNotNull { child -> runCatching { child.stop() }.exceptionOrNull() }
        val trapPort = trap.address.port
        trap.stop(0)
        check(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", trapPort), 100) } }.isFailure) { "Owned model trap port remains open" }
        val alive = owned.sumOf { it.aliveOwnedCount() }
        record("model_requests=${requests.get()} children_alive=$alive temporary_payloads=${if (cleanupFailures.isEmpty()) "deleted" else "cleanup_failed"} cleanup_failures=${cleanupFailures.size} cleanup=${if (cleanupFailures.isEmpty() && alive == 0) "completed" else "failed"}")
        check(alive == 0) { "Owned workers/processes remain alive" }
        assertThat(requests.get()).isEqualTo(0)
        check(cleanupFailures.isEmpty()) { "Owned child cleanup failed: $cleanupFailures" }
        if (platform == Platform.IOS) requireDriverPortClosed()
      }
    }
  }

  private suspend fun marker(child: PackagedChild, marker: String) {
    while (!withContext(Dispatchers.IO) { child.stdout.readText().contains(marker) }) {
      check(child.process.isAlive) { "Missing $marker: ${withContext(Dispatchers.IO) { child.stderr.readText() }}" }
      delay(20)
    }
  }

  private fun requireDriverPortClosed() {
    check(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 22087), 100) } }.isFailure) { "iOS driver port 22087 is occupied or retained; refuse reuse" }
  }

  private suspend fun stdio(directory: File, options: List<String>, target: String, platform: Platform, owned: MutableList<PackagedChild>, record: (String) -> Unit) {
    val child = start(directory, owned, "stdio", options + listOf("mcp"), pipeOutput = true)
    val captured = ByteArrayOutputStream()
    val input = object : FilterInputStream(child.process.inputStream) {
      override fun read(): Int = super.read().also { if (it >= 0) captured.write(it) }
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
        if (it > 0) captured.write(bytes, offset, it)
      }
    }
    val client = Client(Implementation("verity-packaged-smoke", "1"))
    try {
      withTimeout(30_000) { client.connect(StdioClientTransport(input.asSource().buffered(), child.process.outputStream.asSink().buffered())) }
      tools(client, target, platform, record, "stdio")
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
    if (platform == Platform.IOS) withContext(Dispatchers.IO) { requireDriverPortClosed() }
  }

  private suspend fun http(directory: File, options: List<String>, target: String, platform: Platform, owned: MutableList<PackagedChild>, record: (String) -> Unit) {
    val port = withContext(Dispatchers.IO) { ServerSocket(0).use { it.localPort } }
    val child = start(directory, owned, "http", options + listOf("mcp", "--transport", "http", "--host", "127.0.0.1", "--port", port.toString()))
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
      withTimeout(30_000) { client.connect(transport) }
      tools(client, target, platform, record, "http")
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
      if (platform == Platform.IOS) requireDriverPortClosed()
    }
  }

  private suspend fun tools(client: Client, target: String, platform: Platform, record: (String) -> Unit, transport: String) {
    assertThat(client.serverVersion!!.name).isEqualTo("verity")
    assertThat(withTimeout(30_000) { client.listTools().tools.size }).isEqualTo(14)
    suspend fun call(name: String, arguments: JsonObject) = client.callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))
    exercisePackagedTools(platform, target, record, ::call, transport)
  }

  private suspend fun start(directory: File, owned: MutableList<PackagedChild>, name: String, args: List<String>, pipeOutput: Boolean = false): PackagedChild = withContext(Dispatchers.IO) {
    val stdout = File(directory, "$name.stdout")
    val stderr = File(directory, "$name.stderr")
    val scratch = Files.createTempDirectory(directory.toPath(), "scratch-$name-").toFile()
    val builder = ProcessBuilder(listOf(File(System.getProperty("java.home"), "bin/java").path, "-Xmx512m", "-Djava.io.tmpdir=${scratch.absolutePath}") + args)
      .directory(directory).redirectError(stderr)
    builder.environment().remove("CLASSPATH")
    if (!pipeOutput) builder.redirectOutput(stdout)
    try {
      PackagedChild(builder.start(), stdout, stderr, scratch).also { owned += it }
    } catch (failure: Throwable) {
      scratch.deleteRecursively()
      throw failure
    }
  }

  private suspend fun awaitSuccess(child: PackagedChild) {
    while (child.process.isAlive) delay(20)
    check(child.process.exitValue() == 0) { "Child failed: ${withContext(Dispatchers.IO) { child.stderr.readText() }}" }
  }
}

/** Same protocol sequence is exercised by real SDK clients and offline negative fixtures. */
private suspend fun exercisePackagedTools(platform: Platform, target: String, record: (String) -> Unit, call: suspend (String, JsonObject) -> CallToolResult, transport: String = "offline", recordDispatcher: CoroutineDispatcher = Dispatchers.IO) {
  fun text(result: CallToolResult) = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
  fun args(id: String) = buildJsonObject { put("session_id", id) }
  fun diffArgs(id: String, snapshot: String) = buildJsonObject {
    put("session_id", id)
    put("before_snapshot_id", snapshot)
    put("after_snapshot_id", snapshot)
  }
  fun errorCode(result: CallToolResult): String {
    check(result.isError == true) { "Expected rejection: ${text(result)}" }
    return Json.parseToJsonElement(text(result)).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
  }
  suspend fun open(): String {
    val opened = withTimeout(600_000) {
      call(
        "open_session",
        buildJsonObject {
          put("platform", platform.wireName)
          put("device", target)
          put("disable_animations", false)
        },
      )
    }
    check(opened.isError != true) { text(opened) }
    return Regex("session_id: ([a-f0-9-]+)").find(text(opened))?.groupValues?.get(1) ?: error("Missing session ID: ${text(opened)}")
  }
  suspend fun close(id: String) {
    val started = TimeSource.Monotonic.markNow()
    suspend fun stage(value: String) = withContext(NonCancellable + recordDispatcher) {
      record("transport=$transport session_id=$id stage=close_session close=$value elapsed_ms=${started.elapsedNow().inWholeMilliseconds}")
    }
    stage("started")
    try {
      val closed = call("close_session", args(id))
      stage("response")
      check(closed.isError != true) { text(closed) }
      stage("completed")
    } catch (failure: Throwable) {
      stage("failed type=${failure.javaClass.simpleName}")
      throw failure
    }
  }
  val id = open()
  val snapshot = withPackagedSessionClose({ close(id) }) {
    withContext(recordDispatcher) { record("session_id=$id open=completed") }
    withTimeout(120_000) {
      val captured = call(
        "capture_hierarchy",
        buildJsonObject {
          put("session_id", id)
          put("filter", "all")
        },
      )
      val rendered = text(captured)
      check(captured.isError != true && rendered.substringAfter("\n\n", "").isNotBlank()) { "Missing hierarchy payload: $rendered" }
      val snapshot = Regex("snapshot_id: ([a-f0-9-]+)").find(rendered)?.groupValues?.get(1) ?: error("Missing snapshot ID")
      val key = call(
        "press_key",
        buildJsonObject {
          put("session_id", id)
          put("key", if (platform == Platform.IOS) "return" else "BACK")
        },
      )
      check(key.isError != true) { text(key) }
      val diff = call("diff_hierarchy", diffArgs(id, snapshot))
      check(diff.isError != true) { text(diff) }
      withContext(recordDispatcher) { record("session_id=$id snapshot_id=$snapshot hierarchy=nonempty diff=passed") }
      snapshot
    }
  }
  withTimeout(120_000) {
    check(call("capture_hierarchy", args(id)).isError == true) { "Closed session still captures" }
    check(errorCode(call("diff_hierarchy", diffArgs(id, snapshot))) == "session_unavailable") { "Closed session snapshot reuse accepted" }
  }
  // Observable cleared-session semantics: the new session has no captures and cannot reuse an old ID.
  val fresh = open()
  withPackagedSessionClose({ close(fresh) }) {
    withContext(recordDispatcher) { record("session_id=$fresh open=completed") }
    withTimeout(120_000) {
      val empty = call("diff_hierarchy", args(fresh))
      check(errorCode(empty) == "insufficient_captures")
      check(Json.parseToJsonElement(text(empty)).jsonObject["error"]!!.jsonObject["available_captures"]!!.jsonPrimitive.content == "0") { "Fresh session retained captures" }
      check(errorCode(call("diff_hierarchy", diffArgs(fresh, snapshot))) == "snapshot_unavailable")
      withContext(recordDispatcher) { record("snapshot_reuse=closed-session-rejected fresh-session-empty old-id-unavailable") }
    }
  }
}

/** Cleanup gets the existing functional allowance and cannot replace a failed body. */
private suspend fun <T> withPackagedSessionClose(close: suspend () -> Unit, body: suspend () -> T): T {
  var primary: Throwable? = null
  try {
    return body()
  } catch (failure: Throwable) {
    primary = failure
    throw failure
  } finally {
    withContext(NonCancellable) {
      try {
        withTimeout(120_000) { close() }
      } catch (failure: Throwable) {
        if (primary == null) throw failure
        if (failure !== primary) primary.addSuppressed(failure)
      }
    }
  }
}

private class PackagedChild(val process: Process, val stdout: File, val stderr: File, private val scratch: File? = null) {
  private val observed = java.util.concurrent.ConcurrentHashMap<Long, ProcessHandle>()
  fun observeDescendants() {
    process.descendants().use { stream -> stream.forEach { observed[it.pid()] = it } }
  }
  fun aliveOwnedCount(): Int = (if (process.isAlive) 1 else 0) + observed.values.count { it.isAlive }
  fun stop() {
    observeDescendants()
    val descendants = observed.values.toList()
    process.outputStream.close()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
    descendants.forEach { if (it.isAlive) it.destroy() }
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      check(process.waitFor(5, TimeUnit.SECONDS))
    }
    descendants.forEach { descendant ->
      if (descendant.isAlive) {
        runCatching { descendant.onExit().get(5, TimeUnit.SECONDS) }
        if (descendant.isAlive) {
          descendant.destroyForcibly()
          descendant.onExit().get(5, TimeUnit.SECONDS)
        }
      }
    }
    check(!process.isAlive && descendants.none { it.isAlive }) { "Owned process tree remains alive" }
    if (scratch != null && scratch.exists()) check(scratch.deleteRecursively()) { "Owned temporary payload cleanup failed" }
  }
}

/** Exercises the actual packaged CLI parser/list path without device or model work. */
class PackagedCliPlatformOptionTest {
  @Test
  fun `CLI enum names list and distinct wire aliases fail without devices or models`() = runTest {
    withContext(Dispatchers.IO) {
      val jar = File(checkNotNull(System.getProperty("verity.packaged.cli.options.jar")))
      check(jar.isFile)
      val directory = Files.createTempDirectory("packaged-cli-platform-options").toFile()
      val requests = AtomicInteger()
      val trap = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
          requests.incrementAndGet()
          exchange.sendResponseHeaders(500, -1)
          exchange.close()
        }
        start()
      }
      try {
        val journeys = File(directory, "journeys").apply { mkdirs() }
        File(journeys, "settings.journey.yaml").writeText(packagedJourney(Platform.ANDROID_MOBILE))
        for (platform in Platform.entries) {
          // Clikt enum parsing accepts case-only IOS/ios; Android wire names differ semantically.
          val arguments = listOf(platform.cliArgument to true, platform.wireName to (platform == Platform.IOS)) +
            if (platform == Platform.IOS) listOf("ios-simulator" to false) else emptyList()
          for ((argument, accepted) in arguments) {
            val label = argument + if (accepted) "-accepted" else "-rejected"
            val stdout = File(directory, "$label.stdout")
            val stderr = File(directory, "$label.stderr")
            val process = ProcessBuilder(
              File(System.getProperty("java.home"), "bin/java").path, "-Xmx512m", "-jar", jar.absolutePath,
              "--provider", "ollama", "--api-key", "http://127.0.0.1:${trap.address.port}",
              "--platform", argument, "--device", "fixture-no-native-device",
              "list", "--path", journeys.absolutePath,
            ).directory(directory).redirectOutput(stdout).redirectError(stderr).start()
            val child = PackagedChild(process, stdout, stderr)
            try {
              check(process.waitFor(30, TimeUnit.SECONDS)) { "CLI option/list fixture timed out" }
              if (accepted) {
                assertThat(process.exitValue()).isEqualTo(0)
                assertThat(stdout.readText().contains("Settings visible")).isTrue()
              } else {
                assertThat(process.exitValue() != 0).isTrue()
                assertThat(stderr.readText().contains("invalid value for --platform")).isTrue()
              }
            } finally {
              child.stop()
              assertThat(child.aliveOwnedCount()).isEqualTo(0)
            }
          }
        }
        assertThat(requests.get()).isEqualTo(0)
      } finally {
        trap.stop(0)
        check(directory.deleteRecursively())
      }
    }
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

  @Test
  fun `observed descendant is joined even after its owning parent exits`() = fixture(spawnChild = true) { child ->
    withContext(Dispatchers.IO) {
      child.observeDescendants()
      assertThat(child.aliveOwnedCount()).isEqualTo(2)
      child.process.destroy()
      check(child.process.waitFor(5, TimeUnit.SECONDS))
      assertThat(child.aliveOwnedCount()).isEqualTo(1)
      child.stop()
      assertThat(child.aliveOwnedCount()).isEqualTo(0)
    }
  }

  private fun fixture(spawnChild: Boolean = false, block: suspend (PackagedChild) -> Unit) = runBlocking {
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("packaged-child-cleanup").toFile() }
    var child: PackagedChild? = null
    try {
      child = withContext(Dispatchers.IO) {
        val source = File(directory, "PackagedLifecycleFixture.java")
        source.writeText(
          """
          public class PackagedLifecycleFixture {
            public static void main(String[] args) throws Exception {
              if (args.length > 0 && args[0].equals("spawn")) {
                new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Xmx64m", "-cp", System.getProperty("java.class.path"), "PackagedLifecycleFixture", "child").inheritIO().start();
              }
              System.out.println("entered");
              Thread.sleep(300000);
            }
          }
          """.trimIndent(),
        )
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, source.path) == 0)
        val stdout = File(directory, "stdout")
        val stderr = File(directory, "stderr")
        PackagedChild(ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-Xmx512m", "-cp", directory.path, "PackagedLifecycleFixture", if (spawnChild) "spawn" else "child").redirectOutput(stdout).redirectError(stderr).start(), stdout, stderr)
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
private fun packagedJourney(platform: Platform): String = checkNotNull(
  PackagedCliSmoke::class.java.classLoader.getResourceAsStream(
    if (platform == Platform.IOS) "packaged-ios-settings.journey.yaml" else "packaged-android-settings.journey.yaml",
  ),
).bufferedReader().use { it.readText() }

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

  @Test
  fun `iOS fixture uses actual General visibility after implicit launch without any model`() = runTest {
    for (visible in listOf(true, false)) {
      val fake = FakeDeviceSession(platform = Platform.IOS)
      val events = mutableListOf<String>()
      val session = object : DeviceSession by fake {
        override suspend fun executeActions(flow: ActionFlow) = fake.executeActions(flow).also { events += "launch" }
        override suspend fun containsText(text: String, ignoreCase: Boolean): Boolean {
          events += "capture"
          return HierarchyNode(attributes = if (visible) mapOf("text" to "General") else emptyMap()).containsText(text, ignoreCase)
        }
      }
      val result = Orchestrator(
        session = session,
        navigatorFactory = { NavigatorAgent("unused") { _, _ -> error("iOS fixture must not use navigator") } },
        inspectorFactory = { InspectorAgent(evaluateTreeContent = { _, _, _ -> error("iOS fixture must not use inspector") }, evaluateVisualContent = { _, _, _, _ -> error("iOS fixture must not use visual model") }) },
      ).run(JourneyLoader.fromYaml(packagedJourney(Platform.IOS)))
      assertThat(result.passed).isEqualTo(visible)
      assertThat(events).isEqualTo(listOf("launch", "capture"))
      assertThat(fake.executedActionFlows.flatMap { it.actions }).isEqualTo(listOf(Interaction.LaunchApp()))
    }
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
    val journey = JourneyLoader.fromYaml(packagedJourney(Platform.ANDROID_MOBILE))
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

/** Host/artifact guards and protocol cleanup sensitivity use no devices or production sessions. */
class PackagedInputsTest {
  @Test
  fun `all advertised variants require matching host and artifact hashes before setup`() {
    val directory = Files.createTempDirectory("packaged-inputs").toFile()
    try {
      val jar = File(directory, "production.jar").apply { writeText("production-fixture") }
      val probe = File(directory, "probe.jar").apply { writeText("probe-fixture") }
      fun input(variant: String) = PackagedInputs(variant, jar, packagedSha256(jar), probe, packagedSha256(probe))
      input("universal").validate("Linux", "amd64", Platform.ANDROID_MOBILE)
      input("linux-x86_64").validate("Linux", "x86_64", Platform.ANDROID_MOBILE)
      input("macos-aarch64").validate("Mac OS X", "aarch64", Platform.IOS)
      input("universal").validate("Mac OS X", "arm64", Platform.IOS)
      for (invalid in listOf(
        Triple("macos-aarch64", "Linux", "amd64"),
        Triple("linux-x86_64", "Mac OS X", "arm64"),
        Triple("universal", "Linux", "aarch64"),
        Triple("universal", "Windows", "amd64"),
        Triple("unknown", "Linux", "amd64"),
      )) {
        assertFailsWith<IllegalStateException> { input(invalid.first).validate(invalid.second, invalid.third, Platform.ANDROID_MOBILE) }
      }
      assertFailsWith<IllegalStateException> { input("universal").validate("Linux", "amd64", Platform.IOS) }
      val expected = input("universal")
      jar.writeText("changed")
      assertFailsWith<IllegalStateException> { expected.validate("Linux", "amd64", Platform.ANDROID_MOBILE) }
      val expectedProbe = input("universal")
      probe.writeText("changed-probe")
      assertFailsWith<IllegalStateException> { expectedProbe.validate("Linux", "amd64", Platform.ANDROID_MOBILE) }
      probe.delete()
      assertFailsWith<IllegalStateException> { expectedProbe.copy(expectedProbeHash = "missing").validate("Linux", "amd64", Platform.ANDROID_MOBILE) }
    } finally {
      directory.deleteRecursively()
    }
  }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PackagedProtocolSequenceTest {
  @Test
  fun `close beyond ten seconds completes within functional allowance`() = runTest {
    val fixture = ProtocolFixture(Platform.ANDROID_MOBILE, closeDelayMs = 11_000)
    val records = mutableListOf<String>()
    exercisePackagedTools(fixture.platform, "owned-target", records::add, fixture::call, "fixture-stdio", StandardTestDispatcher(testScheduler))
    assertThat(fixture.closed.size).isEqualTo(2)
    assertThat(fixture.calls).isEqualTo(listOf("open_session", "capture_hierarchy", "press_key", "diff_hierarchy", "close_session", "capture_hierarchy", "diff_hierarchy", "open_session", "diff_hierarchy", "diff_hierarchy", "close_session"))
    assertThat(records.count { "transport=fixture-stdio" in it && "close=started" in it }).isEqualTo(2)
    assertThat(records.count { "close=response" in it }).isEqualTo(2)
    assertThat(records.count { "close=completed" in it }).isEqualTo(2)
    assertThat(records.any { "snapshot_reuse=closed-session-rejected fresh-session-empty old-id-unavailable" in it }).isTrue()
    assertThat(testScheduler.currentTime).isEqualTo(22_000L)
  }

  @Test
  fun `close beyond functional allowance still fails`() = runTest {
    val fixture = ProtocolFixture(Platform.ANDROID_MOBILE, closeDelayMs = 120_001)
    val records = mutableListOf<String>()
    assertFailsWith<TimeoutCancellationException> {
      exercisePackagedTools(fixture.platform, "owned-target", records::add, fixture::call, "fixture-http", StandardTestDispatcher(testScheduler))
    }
    assertThat(fixture.closed.isEmpty()).isTrue()
    assertThat(fixture.calls.last()).isEqualTo("close_session")
    assertThat(records.any { "transport=fixture-http" in it && "close=failed type=TimeoutCancellationException" in it }).isTrue()
    assertThat(records.none { "close=completed" in it }).isTrue()
    assertThat(testScheduler.currentTime).isEqualTo(120_000L)
  }

  @Test
  fun `body failure remains primary when close also fails`() = runTest {
    val bodyFailure = IllegalStateException("primary body failure")
    val closeFailure = IllegalArgumentException("close failure")
    val actual = assertFailsWith<IllegalStateException> {
      withPackagedSessionClose({ throw closeFailure }) { throw bodyFailure }
    }
    assertThat(actual === bodyFailure).isTrue()
    assertThat(actual.suppressed.size).isEqualTo(1)
    assertThat(actual.suppressed.single().javaClass).isEqualTo(closeFailure.javaClass)
    assertThat(actual.suppressed.single().message).isEqualTo(closeFailure.message)
  }

  @Test
  fun `both platform keys capture nonempty hierarchy and reject old snapshots after complete close`() = runTest {
    for (platform in listOf(Platform.ANDROID_MOBILE, Platform.IOS)) {
      val fixture = ProtocolFixture(platform)
      execute(fixture)
      assertThat(fixture.closed.size).isEqualTo(2)
      assertThat(fixture.calls).isEqualTo(listOf("open_session", "capture_hierarchy", "press_key", "diff_hierarchy", "close_session", "capture_hierarchy", "diff_hierarchy", "open_session", "diff_hierarchy", "diff_hierarchy", "close_session"))
    }
  }

  @Test
  fun `empty hierarchy still closes the tracked session and fails qualification`() = runTest {
    val fixture = ProtocolFixture(Platform.IOS, emptyHierarchy = true)
    assertFailsWith<IllegalStateException> { execute(fixture) }
    assertThat(fixture.closed.size).isEqualTo(1)
  }

  @Test
  fun `snapshot reuse success after close or in a new session fails qualification`() = runTest {
    for (leak in listOf("closed", "fresh")) {
      val fixture = ProtocolFixture(Platform.ANDROID_MOBILE, leak = leak)
      assertFailsWith<IllegalStateException> { execute(fixture) }
      assertThat(fixture.closed.size).isEqualTo(if (leak == "closed") 1 else 2)
    }
  }

  @Test
  fun `failed explicit session close is a failed qualification`() = runTest {
    val fixture = ProtocolFixture(Platform.IOS, failClose = true)
    assertFailsWith<IllegalStateException> { execute(fixture) }
    assertThat(fixture.calls.last()).isEqualTo("close_session")
  }

  // The real helper performs IO and wall-clock deadlines; keep those off the virtual scheduler.
  private suspend fun execute(fixture: ProtocolFixture) = withContext(Dispatchers.Default) {
    exercisePackagedTools(fixture.platform, "owned-target", {}, fixture::call)
  }

  private class ProtocolFixture(val platform: Platform, val emptyHierarchy: Boolean = false, val leak: String? = null, val failClose: Boolean = false, val closeDelayMs: Long = 0) {
    val calls = mutableListOf<String>()
    val closed = mutableSetOf<String>()
    private var opens = 0
    private val first = "11111111-1111-1111-1111-111111111111"
    private val fresh = "22222222-2222-2222-2222-222222222222"
    private val snapshot = "33333333-3333-3333-3333-333333333333"
    private fun success(text: String) = CallToolResult(content = listOf(TextContent(text)))
    private fun error(code: String) = CallToolResult(content = listOf(TextContent("{\"error\":{\"code\":\"$code\",\"available_captures\":0}}")), isError = true)
    suspend fun call(name: String, arguments: JsonObject): CallToolResult {
      calls += name
      val id = arguments["session_id"]?.jsonPrimitive?.content
      return when (name) {
        "open_session" -> {
          assertThat(arguments["platform"]!!.jsonPrimitive.content).isEqualTo(platform.wireName)
          success("session_id: ${if (opens++ == 0) first else fresh}")
        }

        "close_session" -> {
          delay(closeDelayMs)
          if (failClose) {
            error("close_failed")
          } else {
            closed += checkNotNull(id)
            success("closed")
          }
        }

        "capture_hierarchy" -> if (id in closed) error("session_unavailable") else success("snapshot_id: $snapshot\n\n${if (emptyHierarchy) "" else "[0] text=Settings"}")

        "press_key" -> {
          assertThat(arguments["key"]!!.jsonPrimitive.content).isEqualTo(if (platform == Platform.IOS) "return" else "BACK")
          success("pressed")
        }

        "diff_hierarchy" -> when {
          id in closed -> if (leak == "closed") success("leaked") else error("session_unavailable")
          id == fresh -> if (leak == "fresh") success("leaked") else error(if (arguments.containsKey("before_snapshot_id")) "snapshot_unavailable" else "insufficient_captures")
          else -> success("no changes")
        }

        else -> error("unexpected")
      }
    }
  }
}
