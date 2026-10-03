package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import kotlin.test.Test

class ModelFailureTest {
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
