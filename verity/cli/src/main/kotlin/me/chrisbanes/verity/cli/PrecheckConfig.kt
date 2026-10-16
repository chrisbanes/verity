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
)

data class PrecheckCliOptions(
  val enabled: Boolean? = null,
  val model: String? = null,
  val baseUrl: String? = null,
)

data class ResolvedPrecheck(
  val baseUrl: String,
  val model: String,
  val apiKey: String?,
  val timeout: Duration,
  val passAt: Double,
  val failAt: Double,
) {
  override fun toString(): String = "ResolvedPrecheck(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey == null) "null" else "<redacted>"}, timeout=$timeout, passAt=$passAt, failAt=$failAt)"
}

const val TYPESAFE_BASE_URL = "https://api.typesafe.ai"
private const val TYPESAFE_DEFAULT_MODEL = "jev-latest"
private const val TYPESAFE_API_KEY_ENV = "TYPESAFE_API_KEY"
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

  val baseUrl = (cli.baseUrl ?: config?.baseUrl ?: TYPESAFE_BASE_URL).trimEnd('/')
  validateBaseUrl(baseUrl)
  val typeSafe = baseUrl == TYPESAFE_BASE_URL

  val model = cli.model ?: config?.model ?: TYPESAFE_DEFAULT_MODEL.takeIf { typeSafe }
  require(!model.isNullOrBlank()) { "precheck.model is required unless base-url is $TYPESAFE_BASE_URL" }

  val apiKey = if (cli.baseUrl != null) {
    null
  } else {
    (config?.apiKeyEnv ?: TYPESAFE_API_KEY_ENV.takeIf { typeSafe })?.let { name ->
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

  val passAt = config?.passAt ?: 0.9
  val failAt = config?.failAt ?: 0.1
  require(passAt > failAt) { "precheck.pass-at must be greater than precheck.fail-at" }

  return ResolvedPrecheck(baseUrl, model, apiKey, timeout, passAt, failAt)
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
