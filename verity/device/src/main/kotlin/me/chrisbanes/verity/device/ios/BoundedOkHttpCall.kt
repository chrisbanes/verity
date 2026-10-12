package me.chrisbanes.verity.device.ios

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Runs [block] against a call for [request] that is bounded only by [remainingNanos] and cancelled with the caller.
 *
 * This owns just the call setup and the cancellation bridge; [block] owns `execute`, closing the response and every
 * caller checkpoint. [onCleanup] runs inside `NonCancellable`, before the call is cancelled and the bridge is joined.
 * The bridge is joined before this returns or throws.
 */
internal suspend fun <T> OkHttpClient.executeBounded(
  request: Request,
  remainingNanos: Long,
  onCleanup: () -> Unit = {},
  block: (Call) -> T,
): T = coroutineScope {
  // The caller budget is the only limit; OkHttp defaults would cut a slow cold capture off at 10s.
  val call = newBuilder().callTimeout(remainingNanos, TimeUnit.NANOSECONDS)
    .connectTimeout(0, TimeUnit.NANOSECONDS).readTimeout(0, TimeUnit.NANOSECONDS).writeTimeout(0, TimeUnit.NANOSECONDS)
    .build().newCall(request)
  val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
    try {
      awaitCancellation()
    } finally {
      call.cancel()
    }
  }
  try {
    runInterruptible(Dispatchers.IO) { block(call) }
  } finally {
    withContext(NonCancellable) {
      onCleanup()
      cancellation.cancelAndJoin()
    }
  }
}
