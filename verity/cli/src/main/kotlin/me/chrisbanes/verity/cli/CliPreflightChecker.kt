package me.chrisbanes.verity.cli

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PathPreflightChecker
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.core.preflight.PreflightIssue
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.core.preflight.PreflightSeverity
import me.chrisbanes.verity.device.preflight.DevicePreflightChecker
import me.chrisbanes.verity.device.preflight.PlatformDevicePreflightChecker

data class CliPreflightRequest(
  val cliProvider: String?,
  val cliOpenaiAuth: String? = null,
  val cliNavigatorModel: String?,
  val cliInspectorModel: String?,
  val cliNavigatorEffort: String? = null,
  val cliInspectorEffort: String? = null,
  val apiKey: String?,
  val journeyPath: String?,
  val contextPath: String?,
  val platform: Platform,
  val deviceId: String?,
)

data class CliPreflightResult(
  val report: PreflightReport,
  val provider: VerityProvider?,
  val apiKey: String?,
  val navigatorModel: SelectedRoleModel?,
  val inspectorModel: SelectedRoleModel?,
  val navigatorParams: LLMParams,
  val inspectorParams: LLMParams,
  val backend: ModelRequestBackend? = null,
  val openaiAuth: OpenAiAuth = OpenAiAuth.API_KEY,
  val navigatorEffort: String? = null,
  val inspectorEffort: String? = null,
)

class CliPreflightChecker internal constructor(
  private val environment: (String) -> String? = System::getenv,
  private val pathPreflightChecker: PathPreflightChecker = PathPreflightChecker(),
  private val devicePreflightChecker: DevicePreflightChecker = PlatformDevicePreflightChecker(),
  private val codexFactory: suspend () -> CodexModelBackend = { CodexModelBackend.prepare() },
  private val backendIdentity: (VerityProvider, LLModel) -> String? = ::currentReasoningBackendId,
) {
  suspend fun check(
    request: CliPreflightRequest,
    config: VerityConfig,
    includeDevicePreflight: Boolean = true,
    includeInspectorModelPreflight: Boolean = true,
  ): CliPreflightResult {
    val authValue = request.cliOpenaiAuth ?: config.llm?.openaiAuth
    val auth = try {
      OpenAiAuth.parse(authValue)
    } catch (_: IllegalArgumentException) {
      return authFailure()
    }
    val selectedProvider = runCatching { resolveProvider(request.cliProvider, config) }.getOrNull()
    if (authValue != null && selectedProvider != VerityProvider.OpenAI) return authFailure()
    if (auth == OpenAiAuth.CHATGPT) return checkChatGpt(request, config, includeDevicePreflight, includeInspectorModelPreflight)
    var report = PreflightReport()
    val provider = runCatching { resolveProvider(request.cliProvider, config) }
      .getOrElse { error ->
        report += PreflightReport(
          listOf(
            PreflightIssue(
              code = PreflightCodes.PROVIDER_UNKNOWN,
              severity = PreflightSeverity.ERROR,
              message = error.message ?: "Unknown provider.",
              remediation = "Choose one of: ${VerityProvider.all.joinToString { it.name }}.",
              details = mapOf("provider" to (request.cliProvider ?: config.provider.orEmpty())),
            ),
          ),
        )
        null
      }

    val navigatorModel = provider?.let {
      resolveModelSafely(
        provider = it,
        cliModel = request.cliNavigatorModel,
        configModel = config.effectiveNavigatorModel,
        defaultModel = it.defaultNavigatorModel,
        role = "navigator",
      ) { modelReport -> report += modelReport }
    }
    val inspectorModel = provider?.takeIf { includeInspectorModelPreflight }?.let {
      resolveModelSafely(
        provider = it,
        cliModel = request.cliInspectorModel,
        configModel = config.effectiveInspectorModel,
        defaultModel = it.defaultInspectorModel,
        role = "inspector",
      ) { modelReport -> report += modelReport }
    }

    val navigatorParams = resolveEffortSafely(
      provider = provider,
      model = navigatorModel,
      requested = request.cliNavigatorEffort ?: config.effectiveNavigatorEffort,
      role = "navigator",
    ) { effortReport -> report += effortReport }
    val inspectorParams = if (includeInspectorModelPreflight) {
      resolveEffortSafely(
        provider = provider,
        model = inspectorModel,
        requested = request.cliInspectorEffort ?: config.effectiveInspectorEffort,
        role = "inspector",
      ) { effortReport -> report += effortReport }
    } else {
      LLMParams()
    }
    val effortInvalid = report.errors.any { it.code == PreflightCodes.PROVIDER_EFFORT_UNSUPPORTED }

    val resolvedApiKey = provider?.let { selectedProvider ->
      request.apiKey ?: environment(selectedProvider.envVar)
    }
    if (provider != null && provider.requiresAuth && resolvedApiKey.isNullOrBlank()) {
      report += PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PROVIDER_CREDENTIAL_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "Provider credentials are missing for '${provider.name}'.",
            remediation = "Set ${provider.envVar} or pass --api-key.",
            details = mapOf("provider" to provider.name, "env" to provider.envVar),
          ),
        ),
      )
    }
    if (provider == VerityProvider.Bedrock && environment("AWS_SECRET_ACCESS_KEY").isNullOrBlank()) {
      report += PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PROVIDER_CREDENTIAL_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "Bedrock secret credentials are missing.",
            remediation = "Set AWS_SECRET_ACCESS_KEY for the Bedrock provider.",
            details = mapOf("provider" to provider.name, "env" to "AWS_SECRET_ACCESS_KEY"),
          ),
        ),
      )
    }

    if (request.journeyPath == null) {
      report += PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PATH_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "Journey path is required.",
            remediation = "Run `verity run <path.journey.yaml>`.",
          ),
        ),
      )
    } else {
      val path = Path.of(request.journeyPath)
      report += when {
        Files.isDirectory(path) -> pathPreflightChecker.requireReadableDirectory(path, "Journey directory")
        else -> pathPreflightChecker.requireReadableFile(path, "Journey file")
      }
    }

    if (request.contextPath != null) {
      report += pathPreflightChecker.requireReadableDirectory(Path.of(request.contextPath), "Context path")
    }

    report += pathPreflightChecker.requireTempWritable()
    if (includeDevicePreflight && !effortInvalid) {
      report += devicePreflightChecker.check(request.platform, request.deviceId)
    }

    return CliPreflightResult(
      report = report,
      provider = provider,
      apiKey = resolvedApiKey,
      navigatorModel = navigatorModel?.let(SelectedRoleModel::Api),
      inspectorModel = inspectorModel?.let(SelectedRoleModel::Api),
      navigatorParams = navigatorParams,
      inspectorParams = inspectorParams,
    )
  }

  private fun authFailure(): CliPreflightResult = CliPreflightResult(
    report = codexReport(PreflightCodes.CODEX_AUTH_INVALID, "OpenAI authentication configuration is invalid.", "Choose api-key or chatgpt with provider openai."),
    provider = null,
    apiKey = null,
    navigatorModel = null,
    inspectorModel = null,
    navigatorParams = LLMParams(),
    inspectorParams = LLMParams(),
  )

  private suspend fun checkChatGpt(request: CliPreflightRequest, config: VerityConfig, includeDevice: Boolean, includeInspector: Boolean): CliPreflightResult {
    val navigator = SelectedRoleModel.Codex(request.cliNavigatorModel ?: config.effectiveNavigatorModel ?: CHATGPT_DEFAULT_MODEL_ID)
    val inspector = if (includeInspector) SelectedRoleModel.Codex(request.cliInspectorModel ?: config.effectiveInspectorModel ?: CHATGPT_DEFAULT_MODEL_ID) else null
    val navigatorEffort = request.cliNavigatorEffort ?: config.effectiveNavigatorEffort
    val inspectorEffort = request.cliInspectorEffort ?: config.effectiveInspectorEffort
    var report = withContext(Dispatchers.IO) {
      var paths = if (request.journeyPath == null) {
        codexReport(PreflightCodes.PATH_MISSING, "Journey path is required.", "Pass a journey file or directory.")
      } else {
        val path = Path.of(request.journeyPath)
        if (Files.isDirectory(path)) pathPreflightChecker.requireReadableDirectory(path, "Journey directory") else pathPreflightChecker.requireReadableFile(path, "Journey file")
      }
      request.contextPath?.let { paths += pathPreflightChecker.requireReadableDirectory(Path.of(it), "Context path") }
      paths + pathPreflightChecker.requireTempWritable()
    }
    var backend: CodexModelBackend? = null
    var transferred = false
    var primary: Throwable? = null
    try {
      if (report.passed) {
        try {
          withTimeout(30_000) {
            val prepared = codexFactory().also { backend = it }
            prepared.validateRoles(
              buildList {
                add(navigator to navigatorEffort)
                inspector?.let { add(it to inspectorEffort) }
              },
            )
          }
        } catch (error: TimeoutCancellationException) {
          if (!currentCoroutineContext().isActive) throw error
          report += codexFailureReport(CodexFailureKind.STARTUP_TIMEOUT)
        } catch (error: CancellationException) {
          throw error
        } catch (error: CodexFailure) {
          report += codexFailureReport(error.kind)
        } catch (_: Exception) {
          report += codexFailureReport(CodexFailureKind.PROTOCOL)
        }
      }
      if (report.passed && includeDevice) report += devicePreflightChecker.check(request.platform, request.deviceId)
      if (!report.passed && backend != null) {
        try {
          closeModelBackend(backend)
        } catch (_: CodexFailure) {
          report += codexFailureReport(CodexFailureKind.CLEANUP)
        }
        backend = null
      }
      transferred = report.passed
      return CliPreflightResult(report, VerityProvider.OpenAI, null, navigator, inspector, LLMParams(), LLMParams(), backend.takeIf { transferred }, OpenAiAuth.CHATGPT, navigatorEffort, inspectorEffort)
    } catch (error: Exception) {
      primary = error
      throw error
    } finally {
      if (!transferred) backend?.let { closeModelBackend(it, primary) }
    }
  }

  private fun codexFailureReport(kind: CodexFailureKind): PreflightReport {
    val remediation = when (kind) {
      CodexFailureKind.INSTALLATION -> "Install Codex CLI and make codex available on PATH."
      CodexFailureKind.VERSION -> "Install Codex CLI 0.159.0 or newer with the required app-server protocol."
      CodexFailureKind.HOST -> "Use the qualified macOS host."
      CodexFailureKind.AUTH -> "Sign in to Codex using ChatGPT before running Verity."
      CodexFailureKind.MODEL -> "Choose an exact model ID present in the Codex catalog."
      CodexFailureKind.MODALITY -> "Choose a model with verified text input and image input for the inspector."
      CodexFailureKind.EFFORT -> "Choose an effort supported by the selected Codex model or leave it unset."
      else -> "Check Codex compatibility and isolation; Verity will not enable tools or fall back to API keys."
    }
    val code = when (kind) {
      CodexFailureKind.INSTALLATION -> PreflightCodes.CODEX_INSTALLATION
      CodexFailureKind.VERSION -> PreflightCodes.CODEX_VERSION
      CodexFailureKind.HOST -> PreflightCodes.CODEX_HOST
      CodexFailureKind.PROTOCOL -> PreflightCodes.CODEX_PROTOCOL
      CodexFailureKind.ISOLATION -> PreflightCodes.CODEX_ISOLATION
      CodexFailureKind.STARTUP_TIMEOUT -> PreflightCodes.CODEX_STARTUP_TIMEOUT
      CodexFailureKind.CLEANUP -> PreflightCodes.CODEX_CLEANUP
      CodexFailureKind.REQUEST -> PreflightCodes.CODEX_REQUEST
      CodexFailureKind.AUTH -> PreflightCodes.CODEX_AUTH
      CodexFailureKind.MODEL -> PreflightCodes.CODEX_MODEL
      CodexFailureKind.MODALITY -> PreflightCodes.CODEX_MODALITY
      CodexFailureKind.EFFORT -> PreflightCodes.CODEX_EFFORT
    }
    return codexReport(code, "Codex ${kind.name.lowercase()} preflight failed.", remediation)
  }

  private fun codexReport(code: String, message: String, remediation: String): PreflightReport = PreflightReport(listOf(PreflightIssue(code, PreflightSeverity.ERROR, message, remediation)))

  private fun resolveEffortSafely(
    provider: VerityProvider?,
    model: LLModel?,
    requested: String?,
    role: String,
    addReport: (PreflightReport) -> Unit,
  ): LLMParams {
    if (requested == null || provider == null || model == null) return LLMParams()
    val backendId = backendIdentity(provider, model)
    return try {
      resolveReasoningEffort(provider, model, backendId, requested)
    } catch (error: UnsupportedReasoningEffortException) {
      addReport(
        PreflightReport(
          listOf(
            PreflightIssue(
              code = PreflightCodes.PROVIDER_EFFORT_UNSUPPORTED,
              severity = PreflightSeverity.ERROR,
              message = error.message ?: "Reasoning effort is unsupported.",
              remediation = "Choose a supported effort for this provider, model, and backend, or leave the setting unset.",
              details = mapOf(
                "provider" to provider.name,
                "role" to role,
                "model" to model.id,
                "backend" to (backendId ?: "unvalidated"),
                "requested" to requested,
              ),
            ),
          ),
        ),
      )
      LLMParams()
    }
  }

  private fun resolveModelSafely(
    provider: VerityProvider,
    cliModel: String?,
    configModel: String?,
    defaultModel: LLModel,
    role: String,
    addReport: (PreflightReport) -> Unit,
  ): LLModel? = runCatching {
    resolveModel(cliModel, configModel, defaultModel, provider)
  }.getOrElse { error ->
    addReport(
      PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PROVIDER_MODEL_UNKNOWN,
            severity = PreflightSeverity.ERROR,
            message = error.message ?: "Unknown $role model.",
            remediation = "Choose a supported ${provider.name} model ID.",
            details = mapOf("provider" to provider.name, "role" to role),
          ),
        ),
      ),
    )
    null
  }
}
