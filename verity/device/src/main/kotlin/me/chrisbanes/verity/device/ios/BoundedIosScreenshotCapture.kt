package me.chrisbanes.verity.device.ios

import java.nio.file.Path
import kotlin.time.Duration
import kotlinx.coroutines.coroutineScope
import me.chrisbanes.verity.device.captureBoundedScreenshot
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import xcuitest.XCTestClient

/** Borrows the existing main endpoint and owns only this screenshot call, response and file. */
internal class BoundedIosScreenshotCapture(
  private val endpoint: () -> XCTestClient,
  private val httpClient: OkHttpClient = OkHttpClient(),
  private val checkpoint: () -> Unit = {},
  private val onEvent: (String) -> Unit = {},
) {
  suspend fun capture(output: Path, timeout: Duration) {
    captureBoundedScreenshot(output, timeout, checkpoint, onEvent) { sink, checkCapture, remainingNanos ->
      coroutineScope {
        checkCapture()
        val request = Request.Builder()
          .url(endpoint().xctestAPIBuilder("screenshot").addQueryParameter("compressed", "false").build())
          .get()
          .build()
        checkCapture()
        val remaining = remainingNanos()
        try {
          httpClient.executeBounded(request, remaining) { call ->
            try {
              onEvent("request-entry")
              checkCapture()
              call.execute().use { response ->
                checkCapture()
                check(response.isSuccessful) { "XCTest screenshot HTTP status ${response.code}" }
                checkNotNull(response.body).byteStream().use { input ->
                  val bytes = ByteArray(8192)
                  while (true) {
                    checkCapture()
                    val count = input.read(bytes)
                    checkCapture()
                    if (count < 0) break
                    val buffer = Buffer().write(bytes, 0, count)
                    sink.write(buffer, count.toLong())
                    checkCapture()
                  }
                }
              }
              onEvent("response-closed")
              checkCapture()
            } catch (failure: java.io.IOException) {
              checkCapture()
              throw failure
            }
          }
        } finally {
          onEvent("request-joined")
        }
      }
    }
  }
}
