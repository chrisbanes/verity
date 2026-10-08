package me.chrisbanes.verity.cli

import ai.koog.prompt.message.Message
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.agent.ModelBackendFailure
import me.chrisbanes.verity.agent.ModelBackendFailureKind

class ModelBackendOwnerTest {
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
