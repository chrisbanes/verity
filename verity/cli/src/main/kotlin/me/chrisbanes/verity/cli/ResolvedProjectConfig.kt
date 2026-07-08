package me.chrisbanes.verity.cli

import ai.koog.prompt.llm.LLModel
import java.io.File
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.Platform

data class ProjectCliOptions(
  val journeysPath: String? = null,
  val contextPath: String? = null,
  val outputPath: String? = null,
  val platform: String? = null,
  val deviceId: String? = null,
  val disableAnimations: Boolean? = null,
  val provider: String? = null,
  val navigatorModel: String? = null,
  val inspectorModel: String? = null,
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
  val navigatorModel: LLModel,
  val inspectorModel: LLModel,
  val assertionStrategy: AssertionStrategy,
) {
  companion object {
    fun resolve(
      config: VerityConfig,
      cli: ProjectCliOptions,
    ): ResolvedProjectConfig {
      val provider = resolveProvider(cli.provider, config)
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
        navigatorModel = resolveModel(
          cliModel = cli.navigatorModel,
          configModel = config.effectiveNavigatorModel,
          default = provider.defaultNavigatorModel,
          provider = provider,
        ),
        inspectorModel = resolveModel(
          cliModel = cli.inspectorModel,
          configModel = config.effectiveInspectorModel,
          default = provider.defaultInspectorModel,
          provider = provider,
        ),
        assertionStrategy = resolveAssertionStrategy(
          cli.assertionStrategy ?: config.assertions?.strategy,
        ),
      )
    }
  }
}

fun resolvePlatform(value: String?): Platform? = when (value) {
  null -> null

  "android-tv" -> Platform.ANDROID_TV

  "android" -> Platform.ANDROID_MOBILE

  "ios" -> Platform.IOS

  else -> throw IllegalArgumentException(
    "Invalid device.platform '$value'. Expected one of: android-tv, android, ios",
  )
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
  navigatorModel = navigatorModel.id,
  inspectorModel = inspectorModel.id,
)

val Platform.serialName: String
  get() = when (this) {
    Platform.ANDROID_TV -> "android-tv"
    Platform.ANDROID_MOBILE -> "android"
    Platform.IOS -> "ios"
  }
