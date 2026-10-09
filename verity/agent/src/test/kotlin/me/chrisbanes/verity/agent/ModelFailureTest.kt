package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class ModelFailureTest {
  @Test fun `fixed backend failures preserve stage redact causes and notify observed requests`() = runTest {
    for (stage in ModelRequestStage.entries) {
      for (kind in ModelBackendFailureKind.entries.filter { it != ModelBackendFailureKind.CLEANUP }) {
        val observed = mutableListOf<ModelFailureException>()
        val failure = assertFailsWith<ModelFailureException> { requestModelText(stage, { observed += it }) { throw ModelBackendFailure(kind) } }
        assertThat(failure.stage).isEqualTo(stage)
        assertThat(failure.failure).isEqualTo(if (kind == ModelBackendFailureKind.PROTOCOL) ModelFailureKind.INVALID_RESPONSE else ModelFailureKind.REQUEST)
        assertThat(failure.cause).isEqualTo(null)
        assertThat(observed.single()).isEqualTo(failure)
      }
    }
  }

  @Test fun `cleanup remains typed through every observed and unobserved request stage`() = runTest {
    for (stage in ModelRequestStage.entries) {
      for (observed in listOf(false, true)) {
        val primary = ModelBackendFailure(ModelBackendFailureKind.CLEANUP)
        val callbacks = mutableListOf<ModelFailureException>()
        val failure = assertFailsWith<ModelBackendFailure> {
          requestModelText(stage, if (observed) { error -> callbacks += error } else null) { throw primary }
        }
        assertThat(failure).isSameInstanceAs(primary)
        assertThat(failure.kind).isEqualTo(ModelBackendFailureKind.CLEANUP)
        assertThat(failure.cause).isEqualTo(null)
        assertThat(callbacks).isEqualTo(emptyList())
      }
    }
  }

  @Test fun `legacy backend errors retain the fixed current request stage`() = runTest {
    val failure = assertFailsWith<ModelFailureException> {
      requestModelText(ModelRequestStage.INSPECTOR_TREE) { throw ModelFailureException(ModelRequestStage.NAVIGATOR_FLOW, ModelFailureKind.TIMEOUT) }
    }
    assertThat(failure.stage).isEqualTo(ModelRequestStage.INSPECTOR_TREE)
    assertThat(failure.failure).isEqualTo(ModelFailureKind.REQUEST)
  }

  @Test
  fun `shared diagnostic redaction removes API keys bearer tokens and JWTs`() {
    val diagnostic = redactModelDiagnostic("key=sk-secret-value Authorization: Bearer credential AIzaSensitive api_key=another-secret eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature ordinary")
    assertThat(diagnostic).doesNotContain("sk-secret-value")
    assertThat(diagnostic).doesNotContain("credential")
    assertThat(diagnostic).doesNotContain("eyJhbGciOiJIUzI1NiJ9")
    assertThat(diagnostic).doesNotContain("AIzaSensitive")
    assertThat(diagnostic).doesNotContain("another-secret")
    assertThat(diagnostic).contains("ordinary")
  }
}
