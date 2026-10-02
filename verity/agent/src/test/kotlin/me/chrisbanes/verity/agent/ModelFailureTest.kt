package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import kotlin.test.Test

class ModelFailureTest {
  @Test
  fun `shared diagnostic redaction removes API keys bearer tokens and JWTs`() {
    val diagnostic = redactModelDiagnostic("key=sk-secret-value Authorization: Bearer credential eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature ordinary")
    assertThat(diagnostic).doesNotContain("sk-secret-value")
    assertThat(diagnostic).doesNotContain("credential")
    assertThat(diagnostic).doesNotContain("eyJhbGciOiJIUzI1NiJ9")
    assertThat(diagnostic).contains("ordinary")
  }
}
