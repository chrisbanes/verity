package me.chrisbanes.verity.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.chrisbanes.verity.agent.ModelBackendFailure
import me.chrisbanes.verity.agent.ModelBackendFailureKind

/** A primary failure keeps ownership; cleanup adds only a fixed diagnostic. */
internal suspend fun closeModelBackend(backend: ModelRequestBackend, primary: Throwable? = null) {
  var backendCancellation: CancellationException? = null
  var cancellation: CancellationException? = null
  val closed = withContext(NonCancellable) {
    try {
      withTimeoutOrNull(6_000) {
        try {
          backend.close()
        } catch (failure: CancellationException) {
          backendCancellation = failure
          throw failure
        }
        true
      } == true
    } catch (failure: CancellationException) {
      if (primary == null) {
        cancellation = backendCancellation ?: failure
        true
      } else {
        false
      }
    } catch (_: Exception) {
      false
    }
  }
  cancellation?.let { throw it }
  if (!closed) {
    val cleanup = ModelBackendFailure(ModelBackendFailureKind.CLEANUP)
    if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
  }
}
