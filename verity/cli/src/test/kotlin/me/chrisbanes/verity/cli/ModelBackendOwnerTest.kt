package me.chrisbanes.verity.cli

import ai.koog.prompt.message.Message
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.chrisbanes.verity.agent.ModelBackendFailure
import me.chrisbanes.verity.agent.ModelBackendFailureKind

class ModelBackendOwnerTest {
  private fun backend(close: suspend () -> Unit) = object : ModelRequestBackend {
    override suspend fun execute(request: ModelRequest): Message.Assistant = error("unused")
    override suspend fun close() = close.invoke()
  }

  @Test fun `backend cancellation and foreign timeout retain identity unless an earlier primary owns failure`() = runTest {
    val foreign = assertFailsWith<CancellationException> { withTimeout(1) { delay(100) } }
    for (cancellation in listOf(CancellationException("backend-owned"), foreign)) {
      assertThat(assertFailsWith<CancellationException> { closeModelBackend(backend { throw cancellation }) }).isSameInstanceAs(cancellation)
      for (primary in listOf(IllegalStateException("primary"), CancellationException("caller"))) {
        closeModelBackend(backend { throw cancellation }, primary)
        val diagnostic = primary.suppressed.single() as ModelBackendFailure
        assertThat(diagnostic.kind).isEqualTo(ModelBackendFailureKind.CLEANUP)
        assertThat(diagnostic.cause).isEqualTo(null)
        assertThat(diagnostic.message).isEqualTo("Codex cleanup failure")
      }
    }
  }

  @Test fun `cleanup timeout or failure is fixed and primary cancellation retains ownership`() = runTest {
    for (hang in listOf(false, true)) {
      val backend = object : ModelRequestBackend {
        override suspend fun execute(request: ModelRequest): Message.Assistant = error("unused")
        override suspend fun close() {
          if (hang) delay(60_000)
          error("sk-close-secret")
        }
      }
      val failure = assertFailsWith<ModelBackendFailure> { closeModelBackend(backend) }
      assertThat(failure.kind).isEqualTo(ModelBackendFailureKind.CLEANUP)
      assertThat(failure.cause).isEqualTo(null)
      val primary = CancellationException("caller")
      closeModelBackend(backend, primary)
      assertThat(primary.suppressed.single() is ModelBackendFailure).isEqualTo(true)
      assertThat((primary.suppressed.single() as ModelBackendFailure).kind).isEqualTo(ModelBackendFailureKind.CLEANUP)
    }
  }
}
