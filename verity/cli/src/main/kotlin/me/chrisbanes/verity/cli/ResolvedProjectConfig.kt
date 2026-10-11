package me.chrisbanes.verity.cli

import java.io.File
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.EffortArtifactSetting
import me.chrisbanes.verity.core.result.EffortSettingMode

data class ProjectCliOptions(
  val journeysPath: String? = null,
  val contextPath: String? = null,
  val outputPath: String? = null,
  val platform: String? = null,
  val deviceId: String? = null,
  val disableAnimations: Boolean? = null,
  val provider: String? = null,
  val openaiAuth: String? = null,
  val navigatorModel: String? = null,
  val inspectorModel: String? = null,
  val navigatorEffort: String? = null,
  val inspectorEffort: String? = null,
  val assertionStrategy: String? = null,
)

data class ResolvedProjectConfig(
  val journeysPath: File,
  val configuredJourneysPath: File?,
  val contextPath: File?,
  val outputPath: File,
  val platform: Platform?,
  val deviceId: String?,
  val disableAnimations: Boolean,
  val provider: VerityProvider,
  val navigatorModel: SelectedRoleModel,
  val inspectorModel: SelectedRoleModel,
  val navigatorEffort: String?,
  val inspectorEffort: String?,
  val assertionStrategy: AssertionStrategy,
  val openaiAuth: OpenAiAuth = OpenAiAuth.API_KEY,
) {
  companion object {
    fun resolve(
      config: VerityConfig,
      cli: ProjectCliOptions,
    ): ResolvedProjectConfig {
      val provider = resolveProvider(cli.provider, config)
      val authValue = cli.openaiAuth ?: config.llm?.openaiAuth
      val auth = OpenAiAuth.parse(authValue)
      require(authValue == null || provider == VerityProvider.OpenAI) { "OpenAI authentication requires the openai provider" }
      val configuredJourneysPath = cli.journeysPath ?: config.paths?.journeys
      return ResolvedProjectConfig(
        journeysPath = File(configuredJourneysPath ?: "."),
        configuredJourneysPath = configuredJourneysPath?.let(::File),
        contextPath = (cli.contextPath ?: config.paths?.context)?.let(::File),
        outputPath = File(cli.outputPath ?: config.paths?.output ?: "build/verity"),
        platform = resolvePlatform(cli.platform ?: config.device?.platform),
        deviceId = cli.deviceId ?: config.device?.id,
        disableAnimations = cli.disableAnimations ?: config.device?.disableAnimations ?: false,
        provider = provider,
        openaiAuth = auth,
        navigatorModel = selectRoleModel(
          cliModelId = cli.navigatorModel,
          configModelId = config.effectiveNavigatorModel,
          auth = authValue?.let(OpenAiAuth::parse),
          role = ModelRole.NAVIGATOR,
          provider = provider,
        ),
        inspectorModel = selectRoleModel(
          cliModelId = cli.inspectorModel,
          configModelId = config.effectiveInspectorModel,
          auth = authValue?.let(OpenAiAuth::parse),
          role = ModelRole.INSPECTOR,
          provider = provider,
        ),
        navigatorEffort = cli.navigatorEffort ?: config.effectiveNavigatorEffort,
        inspectorEffort = cli.inspectorEffort ?: config.effectiveInspectorEffort,
        assertionStrategy = resolveAssertionStrategy(
          cli.assertionStrategy ?: config.assertions?.strategy,
        ),
      )
    }
  }
}

fun resolvePlatform(value: String?): Platform? = value?.let {
  requireNotNull(Platform.fromWireName(it)) {
    "Invalid device.platform '$it'. Expected one of: ${Platform.entries.joinToString { p -> p.wireName }}"
  }
}

fun resolveAssertionStrategy(value: String?): AssertionStrategy = value?.let(AssertionStrategy::fromConfig) ?: AssertionStrategy.INFER

fun validateReadableDirectory(path: File, fieldName: String) {
  require(path.isDirectory) {
    "$fieldName must be a directory: ${path.path}"
  }
}

fun validateOutputDirectory(path: File) {
  require(!path.exists() || path.isDirectory) {
    "paths.output must be a directory when it already exists: ${path.path}"
  }
}

fun ResolvedProjectConfig.toRunArtifactMetadata(): RunArtifactMetadata = RunArtifactMetadata(
  provider = provider.name,
  openaiAuth = openaiAuth.takeIf { provider == VerityProvider.OpenAI }?.value,
  modelBackend = if (openaiAuth == OpenAiAuth.CHATGPT) "codex-app-server" else "koog",
  navigatorModel = navigatorModel.id,
  inspectorModel = inspectorModel.id,
  navigatorEffort = navigatorEffort.toEffortArtifactSetting(),
  inspectorEffort = inspectorEffort.toEffortArtifactSetting(),
)

private fun String?.toEffortArtifactSetting(): EffortArtifactSetting = if (this == null) {
  EffortArtifactSetting(EffortSettingMode.BACKEND_DEFAULT)
} else {
  EffortArtifactSetting(EffortSettingMode.EXPLICIT, requested = this)
}
