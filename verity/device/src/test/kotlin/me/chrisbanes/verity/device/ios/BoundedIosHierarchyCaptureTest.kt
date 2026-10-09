package me.chrisbanes.verity.device.ios

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.device.HierarchyCaptureTimeoutException
import me.chrisbanes.verity.device.RecordingXCTestInstaller
import okhttp3.OkHttpClient
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.XCTestInstaller

class BoundedIosHierarchyCaptureTest {
  @Test
  fun `owned transport timeout and caller cancellation finish before reuse and preserve main lifecycle`() = runTest {
    withContext(Dispatchers.Default) {
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val requests = AtomicInteger()
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      val executor = Executors.newSingleThreadExecutor()
      server.executor = executor
      val bytes = "{\"axElement\":{\"label\":\"safe\",\"identifier\":\"id\",\"frame\":{},\"children\":[],\"enabled\":true},\"depth\":0}".toByteArray()
      server.createContext("/") { exchange ->
        try {
          exchange.requestBody.use { it.readBytes() }
          if (requests.incrementAndGet() == 1) {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
          }
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        } catch (_: java.io.IOException) {
          // The test-owned call is canceled; its server response may already be closed.
        } finally {
          exchange.close()
        }
      }
      server.start()
      val endpoint = XCTestClient("127.0.0.1", server.address.port)
      val starts = AtomicInteger()
      val closes = AtomicInteger()
      val installer = RecordingXCTestInstaller(object : XCTestInstaller {
        override fun start(): XCTestClient {
          starts.incrementAndGet()
          return endpoint
        }
        override fun uninstall(): Boolean = error("Unexpected uninstall")
        override fun isChannelAlive() = true
        override fun close() {
          closes.incrementAndGet()
        }
      })
      val http = OkHttpClient()
      val main = XCTestDriverClient(installer, http, false)
      main.restartXCTestRunner()
      val events = ArrayList<String>()
      val capture = BoundedIosHierarchyCapture(installer::current, http, onEvent = events::add)
      try {
        val timedOut = try {
          capture.capture(100.milliseconds)
          false
        } catch (_: HierarchyCaptureTimeoutException) {
          true
        }
        assertThat(timedOut).isEqualTo(true)
        assertThat(events.last()).isEqualTo("worker-complete")
        release.countDown()
        assertThat(capture.capture(2.seconds).attributes["text"]).isEqualTo("safe")
        assertThat(withContext(Dispatchers.IO) { main.viewHierarchy(emptySet(), false).axElement.label }).isEqualTo("safe")
        // Cancel at an owned deterministic decoder checkpoint, never by pretending HTTP callbacks prove parsing.
        val decodeEntered = CountDownLatch(1)
        val caller = async {
          BoundedIosHierarchyCapture(installer::current, http, checkpoint = {
            if (decodeEntered.count == 0L) throw CancellationException("caller checkpoint")
          }, onEvent = { if (it == "decoder-entry") decodeEntered.countDown() }).capture(2.seconds)
        }
        caller.join()
        assertThat(caller.isCancelled).isEqualTo(true)
        assertThat(capture.capture(2.seconds).attributes["resource-id"]).isEqualTo("id")
        assertThat(starts.get()).isEqualTo(1)
        assertThat(closes.get()).isEqualTo(0)
      } finally {
        release.countDown()
        main.close()
        server.stop(0)
        executor.shutdown()
        check(executor.awaitTermination(2, TimeUnit.SECONDS))
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
      }
      assertThat(closes.get()).isEqualTo(1)
    }
  }

  @Test
  fun `caller cancellation during failure cleanup takes priority over capture error and owned deadline`() = runTest {
    withContext(Dispatchers.Default) {
      for (ownedDeadline in listOf(false, true)) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/") { exchange ->
          exchange.requestBody.use { it.readBytes() }
          exchange.sendResponseHeaders(if (ownedDeadline) 200 else 503, 2)
          exchange.responseBody.use { it.write("{}".toByteArray()) }
          exchange.close()
        }
        server.start()
        val cleanup = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completedBody = java.util.concurrent.atomic.AtomicBoolean()
        val observed = AtomicReference<Throwable?>()
        val http = OkHttpClient()
        val helper = BoundedIosHierarchyCapture(
          { XCTestClient("127.0.0.1", server.address.port) },
          http,
          checkpoint = { if (ownedDeadline && completedBody.get()) throw HierarchyCaptureTimeoutException() },
          onEvent = { event ->
            if (event == "response-closed") completedBody.set(true)
            if (event == "cleanup-start") {
              cleanup.countDown()
              check(release.await(2, TimeUnit.SECONDS))
            }
          },
        )
        try {
          val job = async {
            try {
              helper.capture(2.seconds)
            } catch (failure: Throwable) {
              observed.set(failure)
              throw failure
            }
          }
          check(withContext(Dispatchers.IO) { cleanup.await(2, TimeUnit.SECONDS) })
          job.cancel(CancellationException("outer-stop"))
          release.countDown()
          job.join()
          assertThat(observed.get() is CancellationException).isEqualTo(true)
          assertThat(observed.get()?.message).isEqualTo("outer-stop")
        } finally {
          release.countDown()
          server.stop(0)
          executor.shutdown()
          check(executor.awaitTermination(2, TimeUnit.SECONDS))
          http.connectionPool.evictAll()
          http.dispatcher.executorService.shutdown()
        }
      }
    }
  }

  @Test
  fun `record resolved runtime identities for controller fingerprint`() = runTest {
    withContext(Dispatchers.IO) {
      val classes = listOf(okhttp3.OkHttpClient::class.java, com.fasterxml.jackson.core.JsonParser::class.java, com.fasterxml.jackson.databind.ObjectMapper::class.java, kotlinx.coroutines.Dispatchers::class.java, xcuitest.XCTestDriverClient::class.java, maestro.Maestro::class.java)
      val entries = classes.associate { type ->
        val path = java.nio.file.Path.of(type.protectionDomain.codeSource.location.toURI())
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        type.name to kotlinx.serialization.json.JsonObject(mapOf("jar" to kotlinx.serialization.json.JsonPrimitive(path.toString()), "sha256" to kotlinx.serialization.json.JsonPrimitive(hash)))
      }
      val jvm = kotlinx.serialization.json.JsonObject(mapOf("version" to kotlinx.serialization.json.JsonPrimitive(System.getProperty("java.version")), "vendor" to kotlinx.serialization.json.JsonPrimitive(System.getProperty("java.vendor")), "arch" to kotlinx.serialization.json.JsonPrimitive(System.getProperty("os.arch")), "heapBytes" to kotlinx.serialization.json.JsonPrimitive(Runtime.getRuntime().maxMemory())))
      java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "verity-53-runtime-inputs.json"), kotlinx.serialization.json.JsonObject(entries + mapOf("jvm" to jvm)).toString())
    }
  }

  @Test
  fun `invalid budgets make no endpoint or transport call`() = runTest {
    var calls = 0
    val capture = BoundedIosHierarchyCapture(endpoint = {
      calls++
      error("No endpoint work")
    })
    for (duration in listOf(Duration.ZERO, (-1).milliseconds, Duration.INFINITE)) {
      val failure = try {
        capture.capture(duration)
        null
      } catch (failure: IllegalArgumentException) {
        failure
      }
      assertThat(failure != null).isEqualTo(true)
    }
    assertThat(calls).isEqualTo(0)
  }

  @Test
  fun `caller budget outlasts the shared client's shorter read timeout`() = runTest {
    withContext(Dispatchers.Default) {
      val bytes = "{\"axElement\":{\"label\":\"slow\",\"identifier\":\"id\",\"frame\":{},\"children\":[],\"enabled\":true},\"depth\":0}".toByteArray()
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        exchange.requestBody.use { it.readBytes() }
        Thread.sleep(300)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.start()
      try {
        val http = OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build()
        val capture = BoundedIosHierarchyCapture({ XCTestClient("127.0.0.1", server.address.port) }, http)
        assertThat(capture.capture(5.seconds).toString().contains("slow")).isEqualTo(true)
      } finally {
        server.stop(0)
      }
    }
  }
}
