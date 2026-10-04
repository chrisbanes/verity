package me.chrisbanes.verity.device.ios

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.sun.net.httpserver.HttpServer
import hierarchy.AXElement
import hierarchy.AXFrame
import hierarchy.ViewHierarchy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.XCTestInstaller

/** Granted fixed proof of complete direct decoding after HTTP response closure. */
@org.junit.jupiter.api.Tag("ios-bounded-proof")
class BoundedIosHierarchyCaptureProof {
  @Test
  fun `granted complete after-body decoding terminates within inherited allowance`() = runTest(timeout = 30.seconds) {
    withContext(Dispatchers.Default) {
      val ledger = ProofLedger.open()
      try {
        val fixture = fixture()
        val payload = json(fixture).toString().toByteArray()
        check(payload.size == 10_626_729)
        check(sha256(payload) == "8dc9d710a34cfe93113e5f2d34524a308808c889fca76d05076c529b2e481fa4")
        check(Runtime.getRuntime().maxMemory() <= 512L * 1024 * 1024)
        val evidence = mutableListOf("payload bytes=${payload.size} sha256=${sha256(payload)} leaves=25000 branchDepth=128 heapMax=${Runtime.getRuntime().maxMemory()}")
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val mode = AtomicReference("control")
        val entry = AtomicLong()
        val bodyEnd = AtomicLong()
        val callEnd = AtomicLong()
        val responseClosed = AtomicLong()
        val parserClosed = AtomicLong()
        val workerComplete = AtomicLong()
        val body = AtomicReference(payload)
        server.createContext("/") { exchange ->
          try {
            exchange.requestBody.use { it.readBytes() }
            val bytes = body.get()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { output ->
              if (mode.get() == "deadline") {
                output.write(bytes, 0, bytes.size - 1)
                output.flush()
                val release = entry.get() + 90_000_000
                while (System.nanoTime() < release) TimeUnit.NANOSECONDS.sleep(release - System.nanoTime())
                output.write(bytes.last().toInt())
              } else {
                output.write(bytes)
              }
            }
          } catch (_: java.io.IOException) {
            // This server owns only the canceled proof response.
          } finally {
            exchange.close()
          }
        }
        server.executor = executor
        val endpoint = XCTestClient("127.0.0.1", server.address.port)
        val listener = object : EventListener() {
          override fun responseBodyEnd(call: Call, byteCount: Long) {
            bodyEnd.set(System.nanoTime())
          }
          override fun callEnd(call: Call) {
            callEnd.set(System.nanoTime())
          }
        }
        val http = OkHttpClient.Builder().eventListener(listener).build()
        val installer = CountingInstaller(endpoint)
        val mainClient = XCTestDriverClient(installer, http, false)
        mainClient.restartXCTestRunner()
        val capture = BoundedIosHierarchyCapture({ endpoint }, http, onEvent = { event ->
          when {
            event.startsWith("capture-entry:") -> entry.set(event.substringAfter(':').toLong())
            event == "response-closed" -> responseClosed.set(System.nanoTime())
            event == "parser-closed" -> parserClosed.set(System.nanoTime())
            event == "worker-complete" -> workerComplete.set(System.nanoTime())
          }
        })
        val workers = Workers()
        var success = false
        server.start()
        ledger.begin()
        try {
          ledger.reserve("controls")
          val sdk = withContext(Dispatchers.IO) { XcTestTreeConverter.convert(mainClient.viewHierarchy(emptySet(), false).axElement) }
          val control = withContext(workers) { capture.capture(5.seconds) }
          assertThat(control).isEqualTo(sdk)
          assertThat(control.nodeCount()).isEqualTo(25_129)
          assertThat(control.depth()).isEqualTo(129)
          check(workers.threads.isEmpty())
          ledger.complete("controls", "completeSDKParity nodes25129 depth129")
          for (phase in listOf("caller", "deadline")) {
            var covered = false
            while (ledger.remaining(phase) > 0) {
              ledger.reserve(phase)
              mode.set(if (phase == "deadline") "deadline" else "control")
              entry.set(0)
              bodyEnd.set(0)
              callEnd.set(0)
              responseClosed.set(0)
              parserClosed.set(0)
              workerComplete.set(0)
              val completed = AtomicLong()
              val job = async(workers) {
                try {
                  capture.capture(if (phase == "deadline") 100.milliseconds else 5.seconds)
                  "success"
                } catch (cancelled: CancellationException) {
                  "cancelled"
                }
              }
              job.invokeOnCompletion { completed.set(System.nanoTime()) }
              val samplingStart = System.nanoTime()
              var parseAt = 0L
              var sample = emptyList<StackTraceElement>()
              while (!job.isCompleted && System.nanoTime() - samplingStart < 2_000_000_000) {
                sample = workers.threads.keys.map { it.stackTrace.toList() }.firstOrNull { stack ->
                  bodyEnd.get() > 0 && responseClosed.get() > 0 && stack.any { it.className.startsWith("me.chrisbanes.verity.device.ios.XcTestHierarchyDecoder") }
                }.orEmpty()
                if (sample.isNotEmpty()) {
                  parseAt = System.nanoTime()
                  break
                }
                delay(1)
              }
              val cancelAt = if (phase == "caller" && parseAt != 0L) System.nanoTime().also { job.cancel() } else 0L
              job.join()
              val joined = System.nanoTime()
              val started = entry.get()
              val deadline = started + 100_000_000
              val trigger = if (phase == "caller") cancelAt else deadline
              val frames = sample.joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
              val row = "phase=$phase entry=$started bodyEnd=${bodyEnd.get()} callEnd=${callEnd.get()} responseClosed=${responseClosed.get()} parseAt=$parseAt cancelAt=$cancelAt deadline=$deadline parserClosed=${parserClosed.get()} workerComplete=${workerComplete.get()} jobCompleted=${completed.get()} joined=$joined workers=${workers.threads.size} frames=$frames"
              evidence += row
              ledger.complete(phase, row)
              check(workers.threads.isEmpty()) { "Owned capture token remains active" }
              if (parseAt == 0L || (phase == "deadline" && parseAt >= deadline)) continue
              covered = true
              check(trigger > 0 && joined <= trigger + 50_000_000) { "Complete decoder invocation exceeded +50ms" }
              check(parserClosed.get() > 0 && workerComplete.get() > 0)
              if (!job.isCancelled) assertThat(job.await()).isEqualTo("cancelled")
              mode.set("control")
              body.set(json(ViewHierarchy(element("small"), 0)).toString().toByteArray())
              assertThat(capture.capture(2.seconds).nodeCount()).isEqualTo(1)
              assertThat(withContext(Dispatchers.IO) { mainClient.viewHierarchy(emptySet(), false).axElement.identifier }).isEqualTo("small")
              body.set(payload)
              break
            }
            check(covered) { "After-body decoder phase inconclusive within inherited $phase attempts" }
          }
          assertThat(installer.starts).isEqualTo(1)
          assertThat(installer.uninstalls).isEqualTo(0)
          assertThat(installer.closes).isEqualTo(0)
          success = true
        } finally {
          check(workers.threads.isEmpty()) { "Preserve owned worker/server for controller reconciliation" }
          mainClient.close()
          server.stop(0)
          executor.shutdown()
          check(executor.awaitTermination(2, TimeUnit.SECONDS))
          http.connectionPool.evictAll()
          http.dispatcher.executorService.shutdown()
          evidence += "cleanup workers0 starts${installer.starts} observerLifecycle0 success=$success"
          ledger.finish(success, evidence)
        }
      } finally {
        ledger.close()
      }
    }
  }

  /** Fixed controller-owned JSON record, never initialized or reset by this fixture. */
  private class ProofLedger private constructor(
    private val path: Path,
    private var data: kotlinx.serialization.json.JsonObject,
    private val channel: java.nio.channels.FileChannel,
    private val lock: java.nio.channels.FileLock,
  ) {
    private var started = 0L
    private var initialNanos = 0L
    suspend fun begin() {
      started = System.nanoTime()
      initialNanos = number("consumedNanos")
      save(mapOf("status" to JsonPrimitive("running"), "sessionStartedNanos" to JsonPrimitive(started)))
    }
    fun remaining(phase: String): Int = 3 - number(if (phase == "caller") "callerUsed" else "deadlineUsed").toInt()
    suspend fun reserve(phase: String) {
      check(data["pending"] == kotlinx.serialization.json.JsonNull) { "Unfinished reservation requires controller reconciliation" }
      val updates = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("pending" to JsonPrimitive(phase))
      if (phase != "controls") {
        check(remaining(phase) > 0)
        val field = if (phase == "caller") "callerUsed" else "deadlineUsed"
        updates[field] = JsonPrimitive(number(field) + 1)
      }
      save(updates)
    }
    suspend fun complete(phase: String, evidence: String) {
      check((data["pending"] as JsonPrimitive).content == phase)
      val history = (data["events"] as JsonArray).toList() + JsonPrimitive(evidence)
      save(mapOf("pending" to kotlinx.serialization.json.JsonNull, "events" to JsonArray(history)))
    }
    suspend fun finish(success: Boolean, evidence: List<String>) {
      save(mapOf("status" to JsonPrimitive(if (success) "passed" else "failed"), "evidence" to JsonArray(evidence.map(::JsonPrimitive))))
    }
    private fun number(field: String): Long = (data.getValue(field) as JsonPrimitive).content.toLong()
    private suspend fun save(updates: Map<String, kotlinx.serialization.json.JsonElement>) = withContext(Dispatchers.IO) {
      val elapsed = System.nanoTime() - started
      val consumed = initialNanos + elapsed
      data = JsonObject(data.toMap() + updates + mapOf("consumedNanos" to JsonPrimitive(consumed)))
      val temporary = path.resolveSibling("${path.fileName}.pending")
      java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE).use { output ->
        val bytes = java.nio.ByteBuffer.wrap(data.toString().toByteArray())
        while (bytes.hasRemaining()) output.write(bytes)
        output.force(true)
      }
      Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      java.nio.channels.FileChannel.open(path.parent, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
      check(consumed <= 30_000_000_000) { "Inherited aggregate capture allowance exhausted" }
    }
    fun close() {
      lock.release()
      channel.close()
    }
    companion object {
      private val inputs = listOf(
        "gradle/libs.versions.toml", "verity/device/build.gradle.kts",
        "verity/device/src/main/kotlin/me/chrisbanes/verity/device/DeviceSession.kt",
        "verity/device/src/main/kotlin/me/chrisbanes/verity/device/DeviceSessionFactory.kt",
        "verity/device/src/main/kotlin/me/chrisbanes/verity/device/ios/IosDeviceSession.kt",
        "verity/device/src/main/kotlin/me/chrisbanes/verity/device/ios/XcTestTreeConverter.kt",
        "verity/device/src/main/kotlin/me/chrisbanes/verity/device/ios/BoundedIosHierarchyCapture.kt",
        "verity/device/src/main/kotlin/me/chrisbanes/verity/device/ios/XcTestHierarchyDecoder.kt",
        "verity/device/src/test/kotlin/me/chrisbanes/verity/device/ios/BoundedIosHierarchyCaptureProof.kt",
      )
      suspend fun open(): ProofLedger = withContext(Dispatchers.IO) {
        val grant = checkNotNull(System.getenv("VERITY_FOCUS_IOS_PROOF_GRANT_ID")) { "Controller grant required" }
        val path = Path.of(checkNotNull(System.getenv("VERITY_FOCUS_IOS_PROOF_LEDGER")) { "Controller ledger required" }).toAbsolutePath()
        check(Files.isRegularFile(path)) { "Controller must seed the inherited ledger" }
        val channel = java.nio.channels.FileChannel.open(path.resolveSibling("${path.fileName}.lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
        val lock = channel.tryLock() ?: run {
          channel.close()
          error("Another proof owns this ledger")
        }
        try {
          val data = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(path)) as JsonObject
          check((data["schemaVersion"] as JsonPrimitive).content == "1")
          check((data["issue"] as JsonPrimitive).content == "53")
          check((data["phase"] as JsonPrimitive).content == "ios-after-body")
          check((data["base"] as JsonPrimitive).content == "24e5924c96ef626ccc61b5069e540869efe0a02c")
          check((data["plan"] as JsonPrimitive).content == "322ffaeae0e27925fcde630d7a288d701f5ee6b64e0515067dfbf6fd866a7c53")
          check((data["heapBytes"] as JsonPrimitive).content.toLong() == Runtime.getRuntime().maxMemory())
          val runtime = data["runtime"] as JsonObject
          for (type in listOf(okhttp3.OkHttpClient::class.java, com.fasterxml.jackson.core.JsonParser::class.java, com.fasterxml.jackson.databind.ObjectMapper::class.java, kotlinx.coroutines.Dispatchers::class.java, xcuitest.XCTestDriverClient::class.java, maestro.Maestro::class.java)) {
            val jar = Path.of(type.protectionDomain.codeSource.location.toURI())
            check(((runtime[type.name] as JsonObject)["sha256"] as JsonPrimitive).content == sha256(Files.readAllBytes(jar))) { "Changed runtime input: ${type.name}" }
          }
          val jvm = runtime["jvm"] as JsonObject
          check((jvm["version"] as JsonPrimitive).content == System.getProperty("java.version"))
          check((jvm["vendor"] as JsonPrimitive).content == System.getProperty("java.vendor"))
          check((jvm["arch"] as JsonPrimitive).content == System.getProperty("os.arch"))
          check((data["grantId"] as JsonPrimitive).content == grant)
          check((data["status"] as JsonPrimitive).content == "ready")
          check(data["pending"] == kotlinx.serialization.json.JsonNull)
          check((data["callerUsed"] as JsonPrimitive).content.toInt() in 1..2)
          check((data["deadlineUsed"] as JsonPrimitive).content.toInt() in 0..2)
          check((data["consumedNanos"] as JsonPrimitive).content.toLong() in 1_079_773_916..29_999_999_999)
          val manifest = data["inputs"] as JsonObject
          val root = Path.of(System.getProperty("user.dir")).let { if (Files.exists(it.resolve("gradle/libs.versions.toml"))) it else it.resolve("../..").normalize() }
          for (file in inputs) check((manifest[file] as JsonPrimitive).content == sha256(Files.readAllBytes(root.resolve(file)))) { "Changed proof input: $file" }
          ProofLedger(path, data, channel, lock)
        } catch (failure: Throwable) {
          lock.release()
          channel.close()
          throw failure
        }
      }
    }
  }

  companion object {
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }

  private class CountingInstaller(private val endpoint: XCTestClient) : XCTestInstaller {
    var starts = 0
    var uninstalls = 0
    var closes = 0
    override fun start(): XCTestClient {
      starts++
      return endpoint
    }
    override fun uninstall(): Boolean {
      uninstalls++
      return true
    }
    override fun isChannelAlive() = true
    override fun close() {
      closes++
    }
  }

  private class Workers :
    AbstractCoroutineContextElement(Key),
    ThreadContextElement<Unit> {
    companion object Key : CoroutineContext.Key<Workers>
    val threads = ConcurrentHashMap<Thread, Boolean>()
    override fun updateThreadContext(context: CoroutineContext) {
      threads[Thread.currentThread()] = true
    }
    override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
      threads.remove(Thread.currentThread())
    }
  }

  private fun fixture(): ViewHierarchy {
    var branch = element("depth-128")
    for (depth in 127 downTo 1) branch = element("depth-$depth", arrayListOf(branch))
    val children = (0 until 25_000).mapTo(ArrayList()) { element("leaf-$it", label = "x".repeat(128)) }
    children += branch
    return ViewHierarchy(element("root", children), 129)
  }

  private fun element(id: String, children: ArrayList<AXElement> = arrayListOf(), label: String = "") = AXElement(
    label, 0, id, 0, 0L, 0, false, 0, false, "", "", AXFrame(0f, 0f, 100f, 50f), true, "", children,
  )

  private fun json(hierarchy: ViewHierarchy): JsonObject = JsonObject(mapOf("axElement" to json(hierarchy.axElement), "depth" to JsonPrimitive(hierarchy.depth)))

  private fun json(element: AXElement): JsonObject = JsonObject(
    mapOf(
      "label" to JsonPrimitive(element.label), "elementType" to JsonPrimitive(element.elementType),
      "identifier" to JsonPrimitive(element.identifier), "horizontalSizeClass" to JsonPrimitive(element.horizontalSizeClass),
      "windowContextID" to JsonPrimitive(element.windowContextID), "verticalSizeClass" to JsonPrimitive(element.verticalSizeClass),
      "selected" to JsonPrimitive(element.selected), "displayID" to JsonPrimitive(element.displayID),
      "hasFocus" to JsonPrimitive(element.hasFocus), "placeholderValue" to JsonPrimitive(element.placeholderValue),
      "value" to JsonPrimitive(element.value), "enabled" to JsonPrimitive(element.enabled), "title" to JsonPrimitive(element.title),
      "frame" to JsonObject(mapOf("X" to JsonPrimitive(element.frame.x), "Y" to JsonPrimitive(element.frame.y), "Width" to JsonPrimitive(element.frame.width), "Height" to JsonPrimitive(element.frame.height))),
      "children" to JsonArray(element.children.map(::json)),
    ),
  )

  private fun HierarchyNode.nodeCount(): Int = 1 + children.sumOf { it.nodeCount() }
  private fun HierarchyNode.depth(): Int = 1 + (children.maxOfOrNull { it.depth() } ?: 0)
}
