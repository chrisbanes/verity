package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class ModelFailureTest {
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
