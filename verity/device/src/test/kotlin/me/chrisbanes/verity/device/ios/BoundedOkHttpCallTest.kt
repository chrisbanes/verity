package me.chrisbanes.verity.device.ios

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

class BoundedOkHttpCallTest {
  @Test
  fun `returns the block result under the caller budget and ignores shorter shared client timeouts`() = runTest {
    withContext(Dispatchers.IO) {
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        Thread.sleep(300)
        val body = "ok".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        exchange.close()
      }
      server.start()
      try {
        val client = OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build()
        val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()
        var cleanups = 0
        var timeoutNanos = 0L
        val result = client.executeBounded(request, 5_000_000_000, onCleanup = { cleanups++ }) { call ->
          timeoutNanos = call.timeout().timeoutNanos()
          call.execute().use { it.body!!.string() }
        }
        assertThat(result).isEqualTo("ok")
        assertThat(timeoutNanos).isEqualTo(5_000_000_000)
        assertThat(cleanups).isEqualTo(1)
      } finally {
        server.stop(0)
      }
    }
  }

  @Test
  fun `caller cancellation cancels the in-flight call before cleanup runs`() = runTest {
    withContext(Dispatchers.IO) {
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        try {
          entered.countDown()
          release.await(30, TimeUnit.SECONDS)
          exchange.sendResponseHeaders(200, -1)
        } catch (_: java.io.IOException) {
          // The test-owned call has been cancelled.
        } finally {
          exchange.close()
        }
      }
      server.start()
      try {
        val client = OkHttpClient()
        val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        // The block rethrows the IOException, which would otherwise become the cancelled job's failure and reach runTest.
        supervisorScope {
          val deferred = async {
            client.executeBounded(request, 60_000_000_000, onCleanup = { events += "cleanup" }) { call ->
              events += "enter"
              try {
                call.execute().use { }
              } catch (failure: java.io.IOException) {
                events += "io"
                throw failure
              }
            }
          }
          assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
          deferred.cancel(CancellationException("stop"))
          // The server is still holding the request, so only the bridge's call.cancel() can end the blocked read.
          withTimeout(5.seconds) { deferred.join() }
          assertThat(deferred.isCancelled).isTrue()
          assertThat(events.toList()).isEqualTo(listOf("enter", "io", "cleanup"))
        }
      } finally {
        release.countDown()
        server.stop(0)
      }
    }
  }
}
