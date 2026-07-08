package me.chrisbanes.verity.cli

import ai.koog.prompt.llm.LLModel
import java.nio.file.Files
import java.nio.file.Path
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
  val cliNavigatorModel: String?,
  val cliInspectorModel: String?,
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
  val navigatorModel: LLModel?,
  val inspectorModel: LLModel?,
)

class CliPreflightChecker(
  private val environment: (String) -> String? = System::getenv,
  private val pathPreflightChecker: PathPreflightChecker = PathPreflightChecker(),
  private val devicePreflightChecker: DevicePreflightChecker = PlatformDevicePreflightChecker(),
) {
  suspend fun check(
    request: CliPreflightRequest,
    config: VerityConfig,
    includeDevicePreflight: Boolean = true,
    includeInspectorModelPreflight: Boolean = true,
  ): CliPreflightResult {
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
    if (includeDevicePreflight) {
      report += devicePreflightChecker.check(request.platform, request.deviceId)
    }

    return CliPreflightResult(
      report = report,
      provider = provider,
      apiKey = resolvedApiKey,
      navigatorModel = navigatorModel,
      inspectorModel = inspectorModel,
    )
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
