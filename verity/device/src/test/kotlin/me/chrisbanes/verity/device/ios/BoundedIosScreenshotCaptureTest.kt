package me.chrisbanes.verity.device.ios

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.device.RecordingXCTestInstaller
import me.chrisbanes.verity.device.ScreenshotCaptureTimeoutException
import okhttp3.OkHttpClient
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.XCTestInstaller

class BoundedIosScreenshotCaptureTest {
  @Test
  fun `owned timeout and caller cancellation close request before byte-identical bounded and SDK reuse`() = runTest {
    withContext(Dispatchers.Default) {
      for (caller in listOf(false, true)) {
        supervisorScope {
          val entered = CountDownLatch(1)
          val release = CountDownLatch(1)
          val serverExited = CountDownLatch(1)
          val requests = AtomicInteger()
          val starts = AtomicInteger()
          val closes = AtomicInteger()
          val bytes = ByteArray(20001) { it.toByte() }
          val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
          val executor = Executors.newSingleThreadExecutor()
          server.executor = executor
          server.createContext("/") { exchange ->
            val first = requests.incrementAndGet() == 1
            try {
              check(exchange.requestURI.path.endsWith("screenshot"))
              check(exchange.requestURI.query == "compressed=false")
              exchange.requestBody.use { it.readBytes() }
              if (first) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
              }
              exchange.sendResponseHeaders(200, bytes.size.toLong())
              exchange.responseBody.use { it.write(bytes) }
            } catch (_: java.io.IOException) {
              // The test-owned request has been cancelled.
            } finally {
              exchange.close()
              if (first) serverExited.countDown()
            }
          }
          server.start()
          val endpoint = XCTestClient("127.0.0.1", server.address.port)
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
          val helper = BoundedIosScreenshotCapture(installer::current, http, onEvent = events::add)
          val directory = Files.createTempDirectory("ios-screenshot-test")
          val output = directory.resolve("screen.png")
          Files.writeString(output, "prior")
          val invocation = async { helper.capture(output, if (caller) 5.seconds else 300.milliseconds) }
          try {
            check(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            if (caller) invocation.cancel(CancellationException("caller transport stop"))
            invocation.join()
            val failure = runCatching { invocation.await() }.exceptionOrNull()
            if (caller) {
              assertThat(failure?.message).isEqualTo("caller transport stop")
            } else {
              assertThat(failure is ScreenshotCaptureTimeoutException).isEqualTo(true)
            }
            assertThat(events.contains("request-joined")).isEqualTo(true)
            assertThat(Files.readString(output)).isEqualTo("prior")
            Files.list(directory).use { assertThat(it.toList()).isEqualTo(listOf(output)) }
            release.countDown()
            check(withContext(Dispatchers.IO) { serverExited.await(3, TimeUnit.SECONDS) })
            helper.capture(output, 2.seconds)
            assertThat(Files.readAllBytes(output).toList()).isEqualTo(bytes.toList())
            assertThat(withContext(Dispatchers.IO) { main.screenshot(false) }.toList()).isEqualTo(bytes.toList())
            assertThat(starts.get()).isEqualTo(1)
            assertThat(closes.get()).isEqualTo(0)
            assertThat(requests.get()).isEqualTo(3)
          } finally {
            release.countDown()
            invocation.cancel()
            invocation.join()
            main.close()
            server.stop(0)
            executor.shutdown()
            check(executor.awaitTermination(3, TimeUnit.SECONDS))
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            Files.list(directory).use { paths -> paths.forEach(Files::deleteIfExists) }
            Files.delete(directory)
          }
          assertThat(closes.get()).isEqualTo(1)
        }
      }
    }
  }

  @Test
  fun `caller budget outlasts the shared client's shorter read timeout`() = runTest {
    withContext(Dispatchers.IO) {
      val bytes = byteArrayOf(1, 2, 3)
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        Thread.sleep(300)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.start()
      val directory = Files.createTempDirectory("ios-screenshot-test")
      try {
        val http = OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build()
        val output = directory.resolve("screen.png")
        BoundedIosScreenshotCapture({ XCTestClient("127.0.0.1", server.address.port) }, http).capture(output, 5.seconds)
        assertThat(Files.readAllBytes(output).toList()).isEqualTo(bytes.toList())
      } finally {
        server.stop(0)
        directory.toFile().deleteRecursively()
      }
    }
  }
}
