package me.chrisbanes.verity.cli

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.message
import assertk.assertions.startsWith
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class PrecheckConfigTest {
  private val enabled = VerityPrecheckConfig(enabled = true)
  private val keyed = mapOf("TYPESAFE_API_KEY" to "s3cr3t")

  private fun resolve(
    config: VerityPrecheckConfig? = enabled,
    cli: PrecheckCliOptions = PrecheckCliOptions(),
    env: Map<String, String> = keyed,
  ) = resolvePrecheck(config, cli) { env[it] }

  private fun assertRejected(
    config: VerityPrecheckConfig,
    env: Map<String, String> = keyed,
    cli: PrecheckCliOptions = PrecheckCliOptions(),
  ) {
    assertFailure { resolve(config, cli, env) }.all {
      isInstanceOf<IllegalArgumentException>()
      message().isNotNull().startsWith("precheck.")
      message().isNotNull().doesNotContain("s3cr3t")
    }
  }

  @Test
  fun `absent or disabled config resolves to null without validating the rest`() {
    assertThat(resolve(config = null, env = emptyMap())).isNull()
    assertThat(resolve(VerityPrecheckConfig(enabled = false, baseUrl = "ftp://x"), env = emptyMap())).isNull()
  }

  @Test
  fun `typesafe defaults`() {
    val resolved = resolve()!!
    assertThat(resolved.baseUrl).isEqualTo("https://api.typesafe.ai")
    assertThat(resolved.model).isEqualTo("jev-latest")
    assertThat(resolved.apiKey).isEqualTo("s3cr3t")
    assertThat(resolved.timeout).isEqualTo(2.seconds)
    assertThat(resolved.passAt).isEqualTo(0.9)
    assertThat(resolved.failAt).isEqualTo(0.1)
  }

  @Test
  fun `custom base url without api-key-env sends no key`() {
    val resolved = resolve(VerityPrecheckConfig(enabled = true, baseUrl = "https://kev.example", model = "kev-1"))!!
    assertThat(resolved.apiKey).isNull()
  }

  @Test
  fun `cli base url drops the configured key and never reads the environment`() {
    val asked = mutableListOf<String>()
    val resolved = resolvePrecheck(
      VerityPrecheckConfig(enabled = true, baseUrl = "https://kev.example", model = "m", apiKeyEnv = "MY_KEY"),
      PrecheckCliOptions(baseUrl = "https://other.example"),
    ) {
      asked += it
      "secret"
    }!!
    assertThat(resolved.apiKey).isNull()
    assertThat(resolved.baseUrl).isEqualTo("https://other.example")
    assertThat(asked).isEqualTo(emptyList<String>())
  }

  @Test
  fun `invalid sections are rejected with a precheck prefix`() {
    assertRejected(VerityPrecheckConfig(enabled = true, baseUrl = "http://192.168.1.5:11434", model = "m"))
    assertRejected(VerityPrecheckConfig(enabled = true, baseUrl = "http://example.com", model = "m"))
    assertRejected(VerityPrecheckConfig(enabled = true, baseUrl = "https://kev.example"))
    assertRejected(VerityPrecheckConfig(enabled = true, passAt = 0.5, failAt = 0.5))
    assertRejected(VerityPrecheckConfig(enabled = true, passAt = 0.2, failAt = 0.8))
    assertRejected(VerityPrecheckConfig(enabled = true, timeout = "0s"))
    assertRejected(VerityPrecheckConfig(enabled = true, timeout = "-1s"))
    assertRejected(VerityPrecheckConfig(enabled = true, timeout = "soon"))
    assertRejected(VerityPrecheckConfig(enabled = true, baseUrl = "https://u:p@kev.example", model = "m"))
    assertRejected(VerityPrecheckConfig(enabled = true, baseUrl = "https://kev.example?x=1", model = "m"))
    assertRejected(VerityPrecheckConfig(enabled = true, baseUrl = "https:///path", model = "m"))
    assertRejected(VerityPrecheckConfig(enabled = true), env = emptyMap())
    assertRejected(VerityPrecheckConfig(enabled = true), env = mapOf("TYPESAFE_API_KEY" to "  "))
    assertRejected(VerityPrecheckConfig(enabled = true), env = mapOf("TYPESAFE_API_KEY" to "s3cr3t\r"))
  }

  @Test
  fun `unset api-key-env names the variable but not a value`() {
    assertFailure {
      resolve(VerityPrecheckConfig(enabled = true, baseUrl = "https://kev.example", model = "m", apiKeyEnv = "MISSING"), env = emptyMap())
    }.message().isNotNull().all {
      startsWith("precheck.")
      contains("MISSING")
    }
  }

  @Test
  fun `loopback http, trailing slash and out of range thresholds are accepted`() {
    listOf("http://localhost:11434", "http://127.0.0.1:8080", "http://[::1]:11434").forEach {
      assertThat(resolve(VerityPrecheckConfig(enabled = true, baseUrl = it, model = "m"))!!.baseUrl).isEqualTo(it)
    }
    assertThat(resolve(VerityPrecheckConfig(enabled = true, baseUrl = "https://kev.example/", model = "m"))!!.baseUrl).isEqualTo("https://kev.example")
    val shadow = resolve(VerityPrecheckConfig(enabled = true, passAt = 2.0, failAt = -1.0))!!
    assertThat(shadow.passAt).isEqualTo(2.0)
    assertThat(shadow.failAt).isEqualTo(-1.0)
  }

  @Test
  fun `cli overrides config`() {
    assertThat(resolve(VerityPrecheckConfig(enabled = true), PrecheckCliOptions(enabled = false))).isNull()
    assertThat(resolve(null, PrecheckCliOptions(enabled = true, model = "jev-x"))!!.model).isEqualTo("jev-x")
  }

  @Test
  fun `toString redacts the key`() {
    assertThat(resolve()!!.toString()).doesNotContain("s3cr3t")
  }
}
