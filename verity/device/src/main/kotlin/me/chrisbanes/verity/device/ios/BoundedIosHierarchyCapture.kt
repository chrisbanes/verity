package me.chrisbanes.verity.device.ios

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.device.HierarchyCaptureTimeoutException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xcuitest.XCTestClient

/** Borrows only the public endpoint; owns each request, complete body and decoder invocation. */
internal class BoundedIosHierarchyCapture(
  private val endpoint: () -> XCTestClient,
  private val httpClient: OkHttpClient = OkHttpClient(),
  private val checkpoint: () -> Unit = {},
  private val onEvent: (String) -> Unit = {},
) {
  init {
    XcTestHierarchyDecoder.prepare()
  }

  suspend fun capture(timeout: Duration): HierarchyNode {
    val started = System.nanoTime()
    onEvent("capture-entry:$started")
    require(timeout.isPositive() && timeout.isFinite()) { "Capture timeout must be positive and finite" }
    val parent = currentCoroutineContext()
    val deadline = started + timeout.inWholeNanoseconds.coerceAtMost(Long.MAX_VALUE - started)
    fun checkDeadline() {
      parent.ensureActive()
      checkpoint()
      if (System.nanoTime() >= deadline) throw HierarchyCaptureTimeoutException()
    }
    val result = try {
      withTimeoutOrNull(timeout) {
        coroutineScope {
          val context = currentCoroutineContext()
          fun checkCapture() {
            context.ensureActive()
            checkDeadline()
          }
          checkCapture()
          val request = Request.Builder()
            .url(endpoint().xctestAPIBuilder("viewHierarchy").build())
            .addHeader("Content-Type", "application/json")
            .post("{\"appIds\":[],\"excludeKeyboardElements\":false}".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
          checkCapture()
          val remaining = (deadline - System.nanoTime()).coerceAtLeast(1)
          val call = httpClient.newBuilder().callTimeout(remaining, TimeUnit.NANOSECONDS).build().newCall(request)
          val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
              awaitCancellation()
            } finally {
              call.cancel()
            }
          }
          try {
            runInterruptible(Dispatchers.IO) {
              checkCapture()
              val chunks = ArrayList<ByteArray>()
              try {
                call.execute().use { response ->
                  checkCapture()
                  check(response.isSuccessful) { "XCTest hierarchy HTTP status ${response.code}" }
                  checkNotNull(response.body).byteStream().use { input ->
                    while (true) {
                      checkCapture()
                      val chunk = ByteArray(8192)
                      val count = input.read(chunk)
                      checkCapture()
                      if (count < 0) break
                      if (count > 0) chunks += if (count == chunk.size) chunk else chunk.copyOf(count)
                    }
                  }
                }
              } catch (failure: java.io.IOException) {
                checkCapture()
                throw failure
              }
              onEvent("response-closed")
              checkCapture()
              try {
                CheckedReader(InputStreamReader(ChunkInput(chunks, ::checkCapture), Charsets.UTF_8), ::checkCapture).use { reader ->
                  onEvent("decoder-entry")
                  XcTestHierarchyDecoder.decode(reader, ::checkCapture)
                }.also {
                  checkCapture()
                }
              } finally {
                onEvent("parser-closed")
              }
            }
          } finally {
            withContext(NonCancellable) {
              onEvent("cleanup-start")
              cancellation.cancelAndJoin()
            }
            onEvent("worker-complete")
          }
        }
      }
    } catch (failure: Throwable) {
      parent.ensureActive()
      throw failure
    }
    checkDeadline()
    return result ?: throw HierarchyCaptureTimeoutException()
  }

  private class ChunkInput(private val chunks: List<ByteArray>, private val checkpoint: () -> Unit) : InputStream() {
    private var index = 0
    private var offset = 0
    override fun read(): Int {
      val byte = ByteArray(1)
      return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
    }
    override fun read(buffer: ByteArray, destination: Int, length: Int): Int {
      checkpoint()
      if (length == 0) return 0
      while (index < chunks.size && offset == chunks[index].size) {
        index++
        offset = 0
      }
      if (index == chunks.size) return -1
      val count = minOf(length, 8192, chunks[index].size - offset)
      chunks[index].copyInto(buffer, destination, offset, offset + count)
      offset += count
      checkpoint()
      return count
    }
  }

  private class CheckedReader(private val reader: Reader, private val checkpoint: () -> Unit) : Reader() {
    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
      checkpoint()
      return reader.read(buffer, offset, minOf(length, 8192)).also { checkpoint() }
    }
    override fun close() = reader.close()
  }
}
