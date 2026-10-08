package me.chrisbanes.verity.cli

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.chrisbanes.verity.agent.ModelBackendFailure
import me.chrisbanes.verity.agent.ModelBackendFailureKind

/** A primary failure keeps ownership; cleanup adds only a fixed diagnostic. */
internal suspend fun closeModelBackend(backend: ModelRequestBackend, primary: Throwable? = null) = withContext(NonCancellable) {
  val closed = withTimeoutOrNull(6_000) {
    try {
      backend.close()
      true
    } catch (_: Exception) {
      false
    }
  } == true
  if (!closed) {
    val cleanup = ModelBackendFailure(ModelBackendFailureKind.CLEANUP)
    if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
  }
}
