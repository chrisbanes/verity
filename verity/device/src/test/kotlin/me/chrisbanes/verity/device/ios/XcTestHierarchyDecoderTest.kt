package me.chrisbanes.verity.device.ios

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import okhttp3.OkHttpClient
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.XCTestInstaller

class XcTestHierarchyDecoderTest {
  @Test
  fun `complete HTTP decoder matches actual SDK normalization across finite wire variants`() = runTest {
    withContext(Dispatchers.IO) {
      val payload = AtomicReference(root(element()).toByteArray())
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      val executor = Executors.newSingleThreadExecutor()
      server.executor = executor
      server.createContext("/") { exchange ->
        exchange.requestBody.use { it.readBytes() }
        val bytes = payload.get()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
      }
      server.start()
      val endpoint = XCTestClient("127.0.0.1", server.address.port)
      val installer = object : XCTestInstaller {
        override fun start() = endpoint
        override fun uninstall() = error("Unexpected uninstall")
        override fun isChannelAlive() = true
        override fun close() {}
      }
      val http = OkHttpClient()
      val sdk = XCTestDriverClient(installer, http, false)
      sdk.restartXCTestRunner()
      val replacement = BoundedIosHierarchyCapture({ endpoint }, http)
      try {
        for ((name, bytes) in rows()) {
          payload.set(bytes)
          val expected = outcome { withContext(Dispatchers.IO) { XcTestTreeConverter.convert(sdk.viewHierarchy(emptySet(), false).axElement) } }
          val actual = outcome { replacement.capture(2.seconds) }
          check(expected == actual) { "Parity row '$name': SDK=$expected replacement=$actual" }
        }
      } finally {
        sdk.close()
        server.stop(0)
        executor.shutdown()
        check(executor.awaitTermination(2, TimeUnit.SECONDS))
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
      }
    }
  }

  @Test
  fun `checkpoint abort stops token and child work immediately`() {
    var checkpoints = 0
    val failure = runCatching {
      XcTestHierarchyDecoder.decode(root(element("children" to "[${element()},${element()}]")).reader()) {
        checkpoints++
        if (checkpoints == 30) throw CancellationException("test cancellation")
      }
    }.exceptionOrNull()
    assertThat(failure is CancellationException).isEqualTo(true)
    assertThat(checkpoints).isEqualTo(30)
  }

  private suspend fun outcome(capture: suspend () -> HierarchyNode): Any = try {
    capture()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: Exception) {
    "capture_failed"
  }

  private fun rows(): List<Pair<String, ByteArray>> {
    val rows = ArrayList<Pair<String, ByteArray>>()
    fun add(name: String, json: String) {
      rows += name to json.toByteArray()
    }
    add("complete", root(element()))
    add("reversed", root("{${fields().entries.reversed().joinToString { "\"${it.key}\":${it.value}" }}}"))
    add("shuffled", root("{${fields().entries.sortedBy { it.key }.joinToString { "\"${it.key}\":${it.value}" }}}"))
    add("ordered-containers", root(element("children" to "[${element("identifier" to "\"first\"")},${element("identifier" to "\"\"", "label" to "\"\"")},${element("identifier" to "\"last\"")}]")))
    add("unicode", root(element("label" to "\"\\u2603 café 😀 \\\"\\n\"", "value" to "\"${"x".repeat(128)}\"")))
    for (field in listOf("label", "identifier", "value", "title")) add("empty-$field", root(element(field to "\"\"")))
    for (field in listOf("placeholderValue", "value", "title")) {
      add("missing-$field", root(elementWithout(field)))
      add("null-$field", root(element(field to "null")))
    }
    for (field in listOf("elementType", "horizontalSizeClass", "windowContextID", "verticalSizeClass", "selected", "displayID", "hasFocus", "enabled")) {
      add("primitive-missing-$field", root(elementWithout(field)))
      add("primitive-null-$field", root(element(field to "null")))
    }
    for (field in listOf("label", "identifier", "frame", "children")) {
      add("required-missing-$field", root(elementWithout(field)))
      add("required-null-$field", root(element(field to "null")))
      add("early-null-then-valid-$field", root("{\"$field\":null,${element().drop(1)}"))
      add("late-valid-then-null-$field", root(element().dropLast(1) + ",\"$field\":null}"))
      add("late-null-then-valid-$field", root(element(field to "null").dropLast(1) + ",\"$field\":${fields().getValue(field)}}"))
    }
    add("missing-root", "{\"depth\":0}")
    add("null-root", "{\"axElement\":null,\"depth\":0}")
    add("root-late-null", root(element()).dropLast(1) + ",\"axElement\":null}")
    add("missing-depth", "{\"axElement\":${element()}}")
    add("null-depth", "{\"axElement\":${element()},\"depth\":null}")
    add("missing-frame-coordinates", root(element("frame" to "{}")))
    add("coercions", root(element("label" to "123", "identifier" to "true", "elementType" to "\"12\"", "windowContextID" to "\"12\"", "selected" to "\"true\"", "frame" to "{\"X\":\"1.5\",\"Y\":null,\"Width\":10,\"Height\":10}")))
    add("float-int-coercion", root(element("elementType" to "1.5")))
    add("integer-overflow", root(element("elementType" to "2147483648")))
    add("long-overflow", root(element("windowContextID" to "9223372036854775808")))
    add("frame-getters", root(element("frame" to "{\"X\":0,\"Y\":0,\"Width\":100,\"Height\":50,\"left\":12,\"right\":34,\"top\":10,\"bottom\":20,\"boundsString\":\"custom\"}")))
    add("bad-frame-getter", root(element("frame" to "{\"X\":0,\"Y\":0,\"Width\":100,\"Height\":50,\"left\":{}}")))
    add("unknown-frame", root(element("frame" to "{\"unknown\":0}")))
    add("unknown-element", root(element().dropLast(1) + ",\"unknown\":0}"))
    add("unknown-root", root(element()).dropLast(1) + ",\"unknown\":0}")
    add("trailing-root", root(element()) + " {}")
    add("trailing-invalid", root(element()) + " xxx")
    add("malformed", root(element()).dropLast(2))
    for (field in listOf("elementType", "windowContextID", "selected", "placeholderValue")) add("wrong-metadata-$field", root(element(field to "{}")))
    for (children in listOf("null", "{}", "false", "1", "\"x\"", "[null]", "[{}]", "[[{}]]")) add("bad-children-$children", root(element("children" to children)))
    rows += "invalid-utf8" to root(element("label" to "\"BAD\"")).toByteArray().let { bytes ->
      val marker = bytes.toString(Charsets.UTF_8).indexOf("BAD")
      bytes.copyOf().also { it[marker] = 0xc3.toByte() }
    }
    return rows
  }

  private fun fields() = linkedMapOf(
    "label" to "\"label\"", "elementType" to "0", "identifier" to "\"id\"", "horizontalSizeClass" to "0",
    "windowContextID" to "0", "verticalSizeClass" to "0", "selected" to "false", "displayID" to "0",
    "hasFocus" to "true", "placeholderValue" to "\"\"", "value" to "\"value\"",
    "frame" to "{\"X\":0,\"Y\":0,\"Width\":100,\"Height\":50}", "enabled" to "true", "title" to "\"title\"", "children" to "[]",
  )
  private fun element(vararg overrides: Pair<String, String>) = fields().apply { putAll(overrides) }.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }
  private fun elementWithout(field: String) = fields().apply { remove(field) }.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }
  private fun root(element: String) = "{\"axElement\":$element,\"depth\":0}"
}
