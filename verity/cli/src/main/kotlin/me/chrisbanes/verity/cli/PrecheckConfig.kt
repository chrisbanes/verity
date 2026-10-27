package me.chrisbanes.verity.cli

import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VerityPrecheckConfig(
  val enabled: Boolean? = null,
  @SerialName("base-url") val baseUrl: String? = null,
  val model: String? = null,
  @SerialName("api-key-env") val apiKeyEnv: String? = null,
  val timeout: String? = null,
  @SerialName("pass-at") val passAt: Double? = null,
  @SerialName("fail-at") val failAt: Double? = null,
  /** Experimental: also pre-check `visually` loop and wait conditions with the current screenshot (System One at TypeSafe, or Decisions). */
  @SerialName("visual-enabled") val visualEnabled: Boolean? = null,
  /** `system-one` (default) or `decisions`; each provider has its own defaults. */
  val provider: String? = null,
  /** Default true: the inspector evaluates the same evidence once before a confident pass can end a loop or wait. */
  @SerialName("confirm-pass") val confirmPass: Boolean? = null,
)

data class PrecheckCliOptions(
  val enabled: Boolean? = null,
  val model: String? = null,
  val baseUrl: String? = null,
)

enum class PrecheckProvider { SYSTEM_ONE, DECISIONS }

data class ResolvedPrecheck(
  val baseUrl: String,
  val model: String,
  val apiKey: String?,
  val timeout: Duration,
  val passAt: Double,
  val failAt: Double,
  /** Whether `visually` loop and wait conditions are pre-checked with the current screenshot. */
  val visual: Boolean = false,
  val provider: PrecheckProvider = PrecheckProvider.SYSTEM_ONE,
  /** Whether the inspector confirms a confident pass on the same evidence before it ends a loop or wait. */
  val confirmPass: Boolean = true,
) {
  override fun toString(): String = "ResolvedPrecheck(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey == null) "null" else "<redacted>"}, timeout=$timeout, passAt=$passAt, failAt=$failAt, visual=$visual, provider=$provider, confirmPass=$confirmPass)"
}

const val TYPESAFE_BASE_URL = "https://api.typesafe.ai"
private const val TYPESAFE_DEFAULT_MODEL = "jev-latest"
private const val TYPESAFE_API_KEY_ENV = "TYPESAFE_API_KEY"
const val OPENAI_BASE_URL = "https://api.openai.com"
private const val DECISIONS_MODEL = "gpt-6-luna"
private const val OPENAI_API_KEY_ENV = "OPENAI_API_KEY"
private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

/** Whether the pre-check is on, without validating the rest of its configuration. */
fun precheckEnabled(config: VerityPrecheckConfig?, cli: PrecheckCliOptions): Boolean = cli.enabled ?: config?.enabled ?: false

/**
 * Resolves the opt-in pre-check from CLI flags, then config, then defaults; null when disabled.
 * Failures are [IllegalArgumentException]s starting `precheck.` that never include an environment value.
 * The API key is read only for a config or default base URL, never for `--precheck-base-url`.
 */
fun resolvePrecheck(
  config: VerityPrecheckConfig?,
  cli: PrecheckCliOptions,
  env: (String) -> String? = System::getenv,
): ResolvedPrecheck? {
  if (!precheckEnabled(config, cli)) return null

  val provider = when (config?.provider) {
    null, "system-one" -> PrecheckProvider.SYSTEM_ONE
    "decisions" -> PrecheckProvider.DECISIONS
    else -> throw IllegalArgumentException("precheck.provider must be system-one or decisions")
  }
  val decisions = provider == PrecheckProvider.DECISIONS
  val defaultBaseUrl = if (decisions) OPENAI_BASE_URL else TYPESAFE_BASE_URL
  val baseUrl = (cli.baseUrl ?: config?.baseUrl ?: defaultBaseUrl).trimEnd('/')
  validateBaseUrl(baseUrl)
  // A default key (and System One's default model) only ever applies at the provider's own host.
  val atDefaultHost = baseUrl == defaultBaseUrl
  val visual = config?.visualEnabled ?: false
  require(!visual || decisions || baseUrl == TYPESAFE_BASE_URL) { "precheck.visual-enabled requires base-url $TYPESAFE_BASE_URL" }

  val model = cli.model ?: config?.model ?: if (decisions) DECISIONS_MODEL else TYPESAFE_DEFAULT_MODEL.takeIf { atDefaultHost }
  require(!model.isNullOrBlank()) { if (decisions) "precheck.model must not be blank" else "precheck.model is required unless base-url is $TYPESAFE_BASE_URL" }

  val defaultKeyEnv = (if (decisions) OPENAI_API_KEY_ENV else TYPESAFE_API_KEY_ENV).takeIf { atDefaultHost }
  val apiKey = if (cli.baseUrl != null) {
    null
  } else {
    (config?.apiKeyEnv ?: defaultKeyEnv)?.let { name ->
      val key = requireNotNull(env(name)?.takeIf { it.isNotBlank() }) { "precheck.api-key-env: environment variable $name is not set" }
      // A control or non-Latin-1 character (a stray CR from a CRLF key file, say) cannot be sent in a header.
      require(key.none { it.isISOControl() || it.code > 0xFF }) { "precheck.api-key-env: environment variable $name contains an unsupported character" }
      key
    }
  }

  val timeout = config?.timeout?.let {
    try {
      Duration.parse(it)
    } catch (_: IllegalArgumentException) {
      throw IllegalArgumentException("precheck.timeout must be a duration such as 2s")
    }
  } ?: Duration.parse("2s")
  require(timeout.isPositive()) { "precheck.timeout must be positive" }

  val passAt = config?.passAt ?: if (decisions) 0.95 else 0.9
  val failAt = config?.failAt ?: if (decisions) 0.05 else 0.1
  require(passAt > failAt) { "precheck.pass-at must be greater than precheck.fail-at" }

  return ResolvedPrecheck(baseUrl, model, apiKey, timeout, passAt, failAt, visual, provider, config?.confirmPass ?: true)
}

private fun validateBaseUrl(baseUrl: String) {
  val uri = try {
    URI(baseUrl)
  } catch (_: URISyntaxException) {
    throw IllegalArgumentException("precheck.base-url is not a valid URL")
  }
  require(!uri.host.isNullOrEmpty()) { "precheck.base-url must include a host" }
  require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
    "precheck.base-url must not include credentials, a query or a fragment"
  }
  require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in LOOPBACK_HOSTS)) {
    "precheck.base-url must use https (http only for localhost)"
  }
}
