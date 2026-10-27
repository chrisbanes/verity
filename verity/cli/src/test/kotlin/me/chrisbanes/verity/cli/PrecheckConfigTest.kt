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
    assertRejected(VerityPrecheckConfig(enabled = true), env = mapOf("TYPESAFE_API_KEY" to "s3cr3t\u2603"))
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
  fun `confirm-pass defaults to true, can be switched off and decodes from yaml`() {
    assertThat(resolve()!!.confirmPass).isEqualTo(true)
    assertThat(resolve(VerityPrecheckConfig(enabled = true, confirmPass = false))!!.confirmPass).isEqualTo(false)
    assertThat(resolve(VerityPrecheckConfig(enabled = true, provider = "decisions", confirmPass = true), env = mapOf("OPENAI_API_KEY" to "k"))!!.confirmPass).isEqualTo(true)
    assertThat(VerityConfig.fromYaml("precheck:\n  enabled: true\n  confirm-pass: false\n").precheck?.confirmPass).isEqualTo(false)
    assertThat(VerityConfig.fromYaml("precheck:\n  enabled: true\n").precheck?.confirmPass).isNull()
    assertFailure { VerityConfig.fromYaml("precheck:\n  confirm-pass: maybe\n") }
  }

  @Test
  fun `toString redacts the key`() {
    assertThat(resolve()!!.toString()).doesNotContain("s3cr3t")
  }

  @Test
  fun `visual-enabled resolves only with the typesafe base url`() {
    assertThat(resolve(VerityPrecheckConfig(enabled = true, visualEnabled = true))!!.visual).isEqualTo(true)
    assertThat(resolve()!!.visual).isEqualTo(false)
    assertThat(resolve(VerityPrecheckConfig(enabled = false, visualEnabled = true, baseUrl = "https://kev.example"), env = emptyMap())).isNull()
    assertFailure { resolve(VerityPrecheckConfig(enabled = true, visualEnabled = true, baseUrl = "https://kev.example", model = "m")) }
      .message().isNotNull().startsWith("precheck.visual-enabled")
    assertFailure { resolve(VerityPrecheckConfig(enabled = true, visualEnabled = true), PrecheckCliOptions(baseUrl = "http://127.0.0.1:1")) }
      .message().isNotNull().startsWith("precheck.visual-enabled")
  }

  @Test
  fun `visual-enabled decodes from yaml`() {
    val config = VerityConfig.fromYaml("precheck:\n  enabled: true\n  visual-enabled: true\n")
    assertThat(config.precheck?.visualEnabled).isEqualTo(true)
  }

  private val decisions = VerityPrecheckConfig(enabled = true, provider = "decisions")
  private val openAiKeyed = mapOf("OPENAI_API_KEY" to "s3cr3t")

  @Test
  fun `decisions defaults`() {
    val resolved = resolve(decisions, env = openAiKeyed)!!
    assertThat(resolved).isEqualTo(ResolvedPrecheck("https://api.openai.com", "gpt-6-luna", "s3cr3t", 2.seconds, 0.95, 0.05, false, PrecheckProvider.DECISIONS))
  }

  @Test
  fun `omitted and system-one providers resolve identically`() {
    val omitted = resolve()
    assertThat(omitted!!.provider).isEqualTo(PrecheckProvider.SYSTEM_ONE)
    assertThat(resolve(VerityPrecheckConfig(enabled = true, provider = "system-one"))).isEqualTo(omitted)
  }

  @Test
  fun `decisions defaults can each be overridden`() {
    val resolved = resolve(
      VerityPrecheckConfig(enabled = true, provider = "decisions", baseUrl = "https://proxy.example/", model = "m", apiKeyEnv = "MY_KEY", timeout = "5s", passAt = 0.8, failAt = 0.2),
      env = mapOf("MY_KEY" to "k"),
    )!!
    assertThat(resolved).isEqualTo(ResolvedPrecheck("https://proxy.example", "m", "k", 5.seconds, 0.8, 0.2, false, PrecheckProvider.DECISIONS))
    assertThat(resolve(decisions, PrecheckCliOptions(model = "gpt-x"), openAiKeyed)!!.model).isEqualTo("gpt-x")
  }

  @Test
  fun `decisions cli base url drops the key and never reads the environment`() {
    val resolved = resolvePrecheck(decisions, PrecheckCliOptions(baseUrl = "https://other.example")) { error("env must not be read") }!!
    assertThat(resolved.apiKey).isNull()
    assertThat(resolved.model).isEqualTo("gpt-6-luna")
  }

  @Test
  fun `a decisions base url other than openai gets no default key`() {
    val resolved = resolvePrecheck(VerityPrecheckConfig(enabled = true, provider = "decisions", baseUrl = "https://proxy.example"), PrecheckCliOptions()) { error("env must not be read") }!!
    assertThat(resolved.apiKey).isNull()
  }

  @Test
  fun `decisions rejects a missing or unsendable key without leaking it`() {
    assertRejected(decisions, env = emptyMap())
    assertFailure { resolve(decisions, env = emptyMap()) }.message().isNotNull().contains("OPENAI_API_KEY")
    assertRejected(decisions, env = mapOf("OPENAI_API_KEY" to "s3cr3t\r"))
  }

  @Test
  fun `an unknown provider is rejected with a precheck prefix`() {
    assertRejected(VerityPrecheckConfig(enabled = true, provider = "bogus"))
    assertRejected(VerityPrecheckConfig(enabled = true, provider = "Decisions"))
    assertFailure { resolve(VerityPrecheckConfig(enabled = true, provider = "decisions", model = ""), env = openAiKeyed) }.message().isNotNull().startsWith("precheck.model")
  }

  @Test
  fun `visual-enabled with decisions resolves at any base url but system-one still needs typesafe`() {
    assertThat(resolve(VerityPrecheckConfig(enabled = true, provider = "decisions", visualEnabled = true), env = openAiKeyed)!!.visual).isEqualTo(true)
    assertThat(resolve(VerityPrecheckConfig(enabled = true, provider = "decisions", visualEnabled = true, baseUrl = "https://proxy.example"))!!.visual).isEqualTo(true)
    assertRejected(VerityPrecheckConfig(enabled = true, provider = "system-one", visualEnabled = true, baseUrl = "https://kev.example", model = "m"))
  }

  @Test
  fun `decisions toString redacts the key and names the provider`() {
    assertThat(resolve(decisions, env = openAiKeyed)!!.toString()).all {
      doesNotContain("s3cr3t")
      contains("provider=DECISIONS")
    }
  }

  @Test
  fun `provider decodes from yaml`() {
    assertThat(VerityConfig.fromYaml("precheck:\n  enabled: true\n  provider: decisions\n").precheck?.provider).isEqualTo("decisions")
  }
}
