package me.chrisbanes.verity.cli

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.optionalValue
import java.io.File
import java.net.URI
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.JourneyResult
import me.chrisbanes.verity.agent.ModelBackendFailure
import me.chrisbanes.verity.agent.ModelBackendFailureKind
import me.chrisbanes.verity.agent.ModelFailureException
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.Orchestrator
import me.chrisbanes.verity.agent.redactModelDiagnostic
import me.chrisbanes.verity.core.context.ContextBundle
import me.chrisbanes.verity.core.context.ContextLoader
import me.chrisbanes.verity.core.context.ContextValidationException
import me.chrisbanes.verity.core.context.describe
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.InvalidActionFlowException
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.parser.JourneyWarnings
import me.chrisbanes.verity.core.result.ArtifactError
import me.chrisbanes.verity.core.result.ArtifactErrorKind
import me.chrisbanes.verity.core.result.ArtifactStatus
import me.chrisbanes.verity.core.result.AssertionArtifact
import me.chrisbanes.verity.core.result.EffortArtifactSetting
import me.chrisbanes.verity.core.result.EffortSettingMode
import me.chrisbanes.verity.core.result.JourneyArtifactIdentity
import me.chrisbanes.verity.core.result.JourneyArtifactResult
import me.chrisbanes.verity.core.result.SegmentArtifactResult
import me.chrisbanes.verity.core.result.SuiteArtifactSummary
import me.chrisbanes.verity.core.result.SuiteJourneyArtifact
import me.chrisbanes.verity.core.timing.NoOpPhaseTimer
import me.chrisbanes.verity.core.timing.PhaseTimer
import me.chrisbanes.verity.core.timing.RecordingPhaseTimer
import me.chrisbanes.verity.device.ActionFlowPreparationException
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.DeviceSessionFactory
import me.chrisbanes.verity.device.TimingDeviceSession

private const val EXIT_INPUT = 2
private const val EXIT_SETUP = 3
private const val EXIT_JOURNEY = 4
private const val EXIT_MODEL = 5

data class ResolvedJourney(
  val file: File,
  val journey: Journey,
)

data class ResolvedJourneyResult(
  val resolvedJourney: ResolvedJourney,
  val result: JourneyResult,
)

data class RunArtifactMetadata(
  val provider: String,
  val navigatorModel: String,
  val inspectorModel: String,
  val navigatorEffort: EffortArtifactSetting = EffortArtifactSetting(EffortSettingMode.BACKEND_DEFAULT),
  val inspectorEffort: EffortArtifactSetting = EffortArtifactSetting(EffortSettingMode.BACKEND_DEFAULT),
  val openaiAuth: String? = null,
  val modelBackend: String? = null,
)

data class SuiteRunResult(
  val results: List<ResolvedJourneyResult>,
  val metadata: RunArtifactMetadata? = null,
) {
  val passed: Boolean get() = results.all { it.result.passed }
  val passedCount: Int get() = results.count { it.result.passed }
  val failedCount: Int get() = results.count { !it.result.passed }
}

class RunCommand(
  private val loadJourney: (File, AssertionStrategy) -> Journey = JourneyLoader::fromFile,
  private val listJourneyFiles: (File) -> List<File> = JourneyLoader::listJourneyFiles,
  private val loadConfig: (File) -> VerityConfig = VerityConfig::loadOrDefault,
  private val journeyRunner: (suspend (ResolvedJourney, JourneyRunArtifactRecorder) -> JourneyResult)? = null,
  private val suiteRunner: (suspend (List<ResolvedJourney>) -> SuiteRunResult)? = null,
  private val dryRunSuiteRunner: (suspend (List<ResolvedJourney>, File) -> DryRunSuiteReport)? = null,
  private val clock: Clock = Clock.systemUTC(),
  private val createRunArtifacts: suspend (File, Clock, String) -> RunArtifactDirectory = { outputRoot, runClock, suiteSlugSource ->
    RunArtifactWriter(outputRoot, runClock).createRun(suiteSlugSource)
  },
  private val writeSummary: suspend (RunArtifactDirectory, SuiteArtifactSummary) -> Unit = { runArtifacts, summary ->
    runArtifacts.writeSummary(summary)
  },
  private val writeJourneyResult: suspend (RunArtifactDirectory, String, JourneyArtifactResult) -> Unit = { runArtifacts, path, result ->
    runArtifacts.writeJourneyResult(path, result)
  },
  private val preflightChecker: suspend (CliPreflightRequest, VerityConfig, Boolean, Boolean) -> CliPreflightResult =
    { request, config, includeDevice, includeInspector ->
      CliPreflightChecker().check(request, config, includeDevice, includeInspector)
    },
  private val sessionFactory: suspend (Platform, String?, Boolean) -> DeviceSession = DeviceSessionFactory::connect,
  private val clientFactory: (VerityProvider, String) -> LLMClient = { provider, apiKey -> provider.createClient(apiKey) },
  private val environment: (String) -> String? = System::getenv,
  private val precheckFactory: (ResolvedPrecheck, ModelTraceSink?, PhaseTimer) -> SystemOnePrecheck = { config, trace, timer -> SystemOnePrecheck(config, trace, timer) },
) : CliktCommand(name = "run") {
  override fun help(context: Context): String = "Execute a journey file against a connected device"

  private val journeyPath by argument("journey", help = "Path to .journey.yaml file").optional()
  private val dryRun by option(
    "--dry-run",
    help = "Validate journeys and render generated Maestro YAML without device access",
  ).flag()
  private val modelTrace by option(
    "--model-trace",
    help = "Append a sensitive trace of model requests, replies and backend frames to this file",
  )
  private val timing by option(
    "--timing",
    help = "Print per-step model and device durations after the run",
  ).flag()
  private val precheck: Boolean? by option(
    "--precheck",
    help = "Ask a System One classifier before the inspector for loop and wait conditions; sends the accessibility tree to its server",
  ).convert { it.toBooleanStrict() }
    // Only an attached value (`--precheck=false`) is read, so the journey argument isn't consumed.
    .optionalValue(true, acceptsUnattachedValue = false)
  private val precheckModel by option("--precheck-model", help = "System One model for --precheck")
  private val precheckBaseUrl by option("--precheck-base-url", help = "System One server for --precheck; the configured API key is not sent to it")

  // Set once per run before any device work; null when the pre-check is off.
  private var precheckConfig: ResolvedPrecheck? = null
  private var precheckClient: SystemOnePrecheck? = null

  // Only a real run records; the default no-op keeps ordinary runs identical.
  private val recorder: RecordingPhaseTimer? by lazy { if (timing) RecordingPhaseTimer() else null }
  private val timer: PhaseTimer get() = recorder ?: NoOpPhaseTimer

  private fun resolveJourneys(
    path: File,
    assertionStrategy: AssertionStrategy,
    platformOverride: Platform?,
  ): List<ResolvedJourney> {
    if (!path.exists()) {
      throw CliktError("Journey path not found: ${path.absolutePath}")
    }

    return when {
      path.isDirectory -> {
        val files = listJourneyFiles(path)
        if (files.isEmpty()) {
          throw CliktError("No journey files found in: ${path.absolutePath}")
        }
        files.map { file -> resolveJourney(file, assertionStrategy, platformOverride) }
          .also(::requireSinglePlatform)
      }

      path.isFile -> listOf(resolveJourney(path, assertionStrategy, platformOverride))

      else -> throw CliktError("Journey path is not a file or directory: ${path.absolutePath}")
    }
  }

  private fun resolveJourney(
    file: File,
    assertionStrategy: AssertionStrategy,
    platformOverride: Platform?,
  ): ResolvedJourney {
    val journey = try {
      loadJourney(file, assertionStrategy)
    } catch (e: SerializationException) {
      throw CliktError("Failed to load journey ${file.absolutePath}: ${e.message}")
    }
    return ResolvedJourney(
      file = file,
      journey = applyResolvedPlatform(journey, platformOverride),
    )
  }

  private fun requireSinglePlatform(journeys: List<ResolvedJourney>) {
    val platforms = journeys.map { it.journey.platform }.distinct()
    if (platforms.size <= 1) return

    val platformDetails = journeys.joinToString(separator = ", ") { resolved ->
      "${resolved.file.name}: ${resolved.journey.platform}"
    }
    throw CliktError(
      "Directory suites must use a single platform. Found: $platformDetails",
    )
  }

  override fun run() = runBlocking {
    val parent = currentContext.parent?.command as Verity

    val config = try {
      loadConfig(File("verity/config.yaml"))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      throw CliktError(e.message ?: "Failed to load project configuration", statusCode = EXIT_SETUP)
    }
    val cliOptions = parent.projectCliOptions()
    val resolved = try {
      if (dryRun) {
        resolveDryRunProjectConfig(config, cliOptions)
      } else {
        ResolvedProjectConfig.resolve(
          config = config,
          cli = cliOptions,
        )
      }
    } catch (e: IllegalArgumentException) {
      throw CliktError(e.message ?: "Invalid project configuration", statusCode = EXIT_SETUP)
    }
    precheckConfig = if (dryRun) {
      null
    } else {
      try {
        resolvePrecheck(config.precheck, PrecheckCliOptions(precheck, precheckModel, precheckBaseUrl), environment)
      } catch (e: IllegalArgumentException) {
        throw CliktError(e.message ?: "Invalid pre-check configuration", statusCode = EXIT_SETUP)
      }
    }
    val metadata = resolved.toRunArtifactMetadata()
    try {
      validateOutputDirectory(resolved.outputPath)
    } catch (e: IllegalArgumentException) {
      throw CliktError(e.message ?: "Invalid output path", statusCode = EXIT_SETUP)
    }

    val path = try {
      resolveRunJourneyFile(journeyPath, resolved.configuredJourneysPath)
    } catch (e: IllegalArgumentException) {
      val message = e.message ?: "Invalid journey path"
      val inputPath = journeyPath ?: resolved.configuredJourneysPath?.path ?: "<unresolved>"
      val runArtifacts = createRunArtifactsOrExit(resolved.outputPath, journeyPath ?: resolved.configuredJourneysPath?.path ?: "run")
      writeParserFailureSummaryOrExitSetup(runArtifacts, inputPath, message, metadata)
      throw CliktError(message, statusCode = EXIT_INPUT)
    }
    val runArtifacts = createRunArtifactsOrExit(resolved.outputPath, path.nameWithoutExtension.ifEmpty { path.name })
    val journeys = try {
      resolveJourneys(
        path = path,
        assertionStrategy = resolved.assertionStrategy,
        platformOverride = resolved.platform,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val message = e.message ?: "Failed to resolve journeys"
      writeParserFailureSummaryOrExitSetup(runArtifacts, path.path, message, metadata)
      throw CliktError(message, statusCode = EXIT_INPUT)
    }
    journeys.forEach { JourneyWarnings.check(it.journey, it.file.path).forEach { warning -> echo(warning, err = true) } }

    if (dryRun) {
      val dryRunReport = try {
        dryRunSuiteRunner?.invoke(journeys, resolved.outputPath)
          ?: runDryRun(parent, config, resolved, path, journeys)
      } catch (error: CancellationException) {
        throw error
      } catch (error: ModelFailureException) {
        throw CliktError(redactModelDiagnostic("Dry-run generation failed: ${error.message}"), statusCode = EXIT_MODEL)
      } catch (error: ActionFlowPreparationException) {
        throw CliktError(redactModelDiagnostic("Dry-run generation failed: ${error.message}"), statusCode = EXIT_SETUP)
      } catch (error: CliktError) {
        if (error.statusCode == EXIT_MODEL || error.statusCode == EXIT_SETUP) throw error
        throw CliktError("Dry-run setup failed", statusCode = EXIT_SETUP)
      } catch (_: Exception) {
        throw CliktError("Dry-run setup failed", statusCode = EXIT_SETUP)
      }
      echo(DryRunRenderer.renderSuite(dryRunReport))
      return@runBlocking
    }

    try {
      val suiteResult = try {
        if (journeyRunner != null) {
          runResolvedJourneysWithArtifacts(journeys, runArtifacts, journeyRunner)
        } else if (suiteRunner != null) {
          try {
            suiteRunner.invoke(journeys)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            throw JourneyExecutionFailure(e.message ?: "Journey suite failed", e, journeys.singleOrNull())
          }
        } else {
          runSuiteWithDevice(
            parent = parent,
            config = config,
            resolved = resolved,
            path = path,
            journeys = journeys,
            runArtifacts = runArtifacts,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: JourneyExecutionFailure) {
        val message = e.message ?: "Journey suite failed"
        try {
          writeJourneyFailureSummary(
            runArtifacts = runArtifacts,
            inputPath = path.path,
            message = message,
            journeys = journeys,
            metadata = metadata,
            failedJourney = e.resolvedJourney ?: journeys.singleOrNull()?.takeIf { candidate -> e.completedResults.none { it.resolvedJourney == candidate } },
            failedAt = e.failedAt,
            completedResults = e.completedResults,
            kind = e.kind,
          )
        } catch (artifactError: CancellationException) {
          throw artifactError
        } catch (artifactError: Exception) {
          val artifactMessage = artifactError.message ?: "Failed to write journey failure artifacts"
          writeSetupFailureSummary(runArtifacts, path.path, artifactMessage, metadata = metadata)
          throw CliktError(artifactMessage, statusCode = EXIT_SETUP)
        }
        throw CliktError(
          message,
          statusCode = when (e.kind) {
            ArtifactErrorKind.SETUP_FAILURE -> EXIT_SETUP
            ArtifactErrorKind.MODEL_FAILURE -> EXIT_MODEL
            else -> EXIT_JOURNEY
          },
        )
      } catch (e: Exception) {
        val message = e.message ?: "Journey suite setup failed"
        writeSetupFailureSummary(runArtifacts, path.path, message, metadata = metadata)
        throw CliktError(message, statusCode = EXIT_SETUP)
      }

      printSuiteResult(suiteResult)
      try {
        writeSuiteArtifacts(path, suiteResult, runArtifacts, metadata)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val message = e.message ?: "Failed to write run artifacts"
        writeSetupFailureSummary(runArtifacts, path.path, message, metadata = metadata)
        throw CliktError(message, statusCode = EXIT_SETUP)
      }

      if (!suiteResult.passed) {
        throw CliktError("Journey suite failed", statusCode = EXIT_JOURNEY)
      }
    } finally {
      // Also on failure: a slow failing run is the one worth measuring.
      recorder?.let { echo(it.report().render()) }
      if (recorder != null) precheckClient?.let { echo(it.stats.render()) }
    }
  }

  private fun resolveDryRunProjectConfig(
    config: VerityConfig,
    cli: ProjectCliOptions,
  ): ResolvedProjectConfig = ResolvedProjectConfig.resolve(
    config = config.copy(
      llm = config.llm?.copy(
        provider = null,
        openaiAuth = null,
        navigatorModel = null,
        inspectorModel = null,
      ),
      provider = null,
      navigatorModel = null,
      inspectorModel = null,
    ),
    cli = cli.copy(
      provider = "ollama",
      openaiAuth = null,
      navigatorModel = null,
      inspectorModel = null,
    ),
  )

  private suspend fun runDryRun(
    parent: Verity,
    config: VerityConfig,
    resolved: ResolvedProjectConfig,
    path: File,
    journeys: List<ResolvedJourney>,
  ): DryRunSuiteReport {
    val contextDir = resolved.contextPath
    val requireContext = resolveRequiredContext(parent.requireContext, config)
    val projectContext = loadProjectContext(contextDir, requireContext) { throw CliktError(redactModelDiagnostic(it), statusCode = EXIT_SETUP) }
    projectContext.describe(contextDir, requireContext) { "  - ${it.displayPath()}" }.forEach { echo(it) }

    var backend: ModelRequestBackend? = null
    var trace: ModelTrace? = null
    var primary: Throwable? = null
    try {
      val planner = DryRunPlanner(
        context = projectContext.text,
        // DryRunPlanner memoizes the navigator, so the backend is acquired at most once.
        navigatorFactory = {
          createDryRunNavigator(parent, config, resolved, path, journeys.first().journey.platform, { trace = it }) { backend = it }
        },
      )
      val suiteReport = DryRunSuiteReport(journeys.map { resolvedJourney -> planner.plan(resolvedJourney) })
      return try {
        DryRunArtifactWriter().write(resolved.outputPath, suiteReport)
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        throw CliktError("Dry-run report write failed", statusCode = EXIT_SETUP)
      }
    } catch (error: Exception) {
      primary = error
      throw error
    } finally {
      try {
        backend?.let { closeModelBackend(it, primary) }
      } finally {
        trace?.close()
      }
    }
  }

  private suspend fun createDryRunNavigator(
    parent: Verity,
    config: VerityConfig,
    resolved: ResolvedProjectConfig,
    path: File,
    platform: Platform,
    traced: (ModelTrace) -> Unit,
    acquired: (ModelRequestBackend) -> Unit,
  ): DryRunNavigator {
    val preflight = preflightChecker(
      parent.preflightRequest(path, platform, resolved.deviceId),
      config,
      false,
      false,
    )
    preflight.trace?.let(traced)
    if (!preflight.report.passed) throw CliktError(redactModelDiagnostic(preflight.report.renderPlainText()), statusCode = EXIT_SETUP)
    val backend = backendFor(preflight).also(acquired)
    val navigatorAgent = navigatorAgent(parent, backend, preflight)
    return DryRunNavigator { actions, appId, targetPlatform, context -> navigatorAgent.generate(actions, appId, targetPlatform, context) }
  }

  private fun Verity.preflightRequest(path: File, platform: Platform, deviceId: String?) = CliPreflightRequest(
    modelTracePath = this@RunCommand.modelTrace,
    timer = this@RunCommand.timer,
    cliProvider = provider,
    cliOpenaiAuth = openaiAuth,
    cliNavigatorModel = navigatorModel,
    cliInspectorModel = inspectorModel,
    cliNavigatorEffort = navigatorEffort,
    cliInspectorEffort = inspectorEffort,
    apiKey = apiKey,
    journeyPath = path.path,
    contextPath = null,
    platform = platform,
    deviceId = deviceId,
  )

  private suspend fun loadProjectContext(
    contextDir: File?,
    required: Boolean,
    onInvalid: (String) -> Nothing,
  ): ContextBundle = try {
    withContext(Dispatchers.IO) {
      ContextLoader.loadProject(directory = contextDir, required = required)
    }
  } catch (e: ContextValidationException) {
    onInvalid(e.message ?: "Project context validation failed")
  }

  private fun navigatorAgent(parent: Verity, backend: ModelRequestBackend, preflight: CliPreflightResult) = NavigatorAgent(
    bundledContext = if (parent.noBundledContext) "" else ContextLoader.loadBundledActions(),
    executeRequest = { systemPrompt, userMessage ->
      backend.execute(ModelRequest("navigator", systemPrompt, userMessage, emptyList(), checkNotNull(preflight.navigatorModel), navigatorSettings(preflight)))
    },
  )

  private fun backendFor(preflight: CliPreflightResult): ModelRequestBackend {
    val backend = preflight.backend ?: run {
      check(preflight.openaiAuth != OpenAiAuth.CHATGPT) { "Prepared Codex backend is required" }
      ApiModelRequestBackend(MultiLLMPromptExecutor(clientFactory(checkNotNull(preflight.provider), preflight.apiKey.orEmpty())))
    }
    val timed = if (timing) TimedModelRequestBackend(backend, timer) else backend
    return preflight.trace?.let { TracingModelRequestBackend(timed, it) } ?: timed
  }

  private fun navigatorSettings(preflight: CliPreflightResult): ModelRequestSettings = when (checkNotNull(preflight.navigatorModel)) {
    is SelectedRoleModel.Api -> ModelRequestSettings.Api(preflight.navigatorParams)
    is SelectedRoleModel.Codex -> ModelRequestSettings.Codex(preflight.navigatorEffort)
  }

  private fun inspectorSettings(preflight: CliPreflightResult): ModelRequestSettings = when (checkNotNull(preflight.inspectorModel)) {
    is SelectedRoleModel.Api -> ModelRequestSettings.Api(preflight.inspectorParams)
    is SelectedRoleModel.Codex -> ModelRequestSettings.Codex(preflight.inspectorEffort)
  }

  private suspend fun createRunArtifactsOrExit(
    outputPath: File,
    suiteSlugSource: String,
  ): RunArtifactDirectory = try {
    createRunArtifacts(outputPath, clock, suiteSlugSource)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    throw CliktError(e.message ?: "Failed to create run artifact directory", statusCode = EXIT_SETUP)
  }

  private suspend fun writeParserFailureSummaryOrExitSetup(
    runArtifacts: RunArtifactDirectory,
    inputPath: String,
    message: String,
    metadata: RunArtifactMetadata? = null,
  ) {
    try {
      writeParserFailureSummary(runArtifacts, inputPath, message, metadata)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val artifactMessage = e.message ?: "Failed to write parser failure summary"
      writeSetupFailureSummary(runArtifacts, inputPath, artifactMessage, metadata = metadata)
      throw CliktError(artifactMessage, statusCode = EXIT_SETUP)
    }
  }

  private suspend fun writeParserFailureSummary(
    runArtifacts: RunArtifactDirectory,
    inputPath: String,
    message: String,
    metadata: RunArtifactMetadata? = null,
  ) {
    writeSummary(runArtifacts, summary(inputPath, ArtifactError(ArtifactErrorKind.PARSER_FAILURE, message), metadata))
  }

  private suspend fun writeSetupFailureSummary(
    runArtifacts: RunArtifactDirectory,
    inputPath: String,
    message: String,
    metadata: RunArtifactMetadata? = null,
  ) {
    try {
      writeSummary(runArtifacts, summary(inputPath, ArtifactError(ArtifactErrorKind.SETUP_FAILURE, message), metadata))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Keep setup failures controlled even when the failure is summary writing itself.
    }
  }

  private suspend fun writeJourneyFailureSummary(
    runArtifacts: RunArtifactDirectory,
    inputPath: String,
    message: String,
    journeys: List<ResolvedJourney>,
    metadata: RunArtifactMetadata? = null,
    failedJourney: ResolvedJourney? = null,
    failedAt: Int? = null,
    completedResults: List<ResolvedJourneyResult> = emptyList(),
    kind: ArtifactErrorKind = ArtifactErrorKind.JOURNEY_FAILURE,
  ) {
    fun indexOf(journey: ResolvedJourney) = journeys.indexOf(journey).takeIf { it >= 0 }?.plus(1) ?: 1
    val completedRefs = completedResults.map { item ->
      writeJourneyArtifact(runArtifacts, indexOf(item.resolvedJourney), item.resolvedJourney, item.toArtifactResult())
    }
    val failedRef = failedJourney?.let { item ->
      writeJourneyArtifact(runArtifacts, indexOf(item), item, item.toFailureArtifactResult(message, failedAt, kind))
    }
    writeSummary(
      runArtifacts,
      summary(inputPath, ArtifactError(kind, message), metadata, completedRefs + listOfNotNull(failedRef), journeys.firstOrNull()?.journey?.platform),
    )
  }

  private suspend fun writeSuiteArtifacts(
    path: File,
    suiteResult: SuiteRunResult,
    runArtifacts: RunArtifactDirectory,
    metadata: RunArtifactMetadata?,
  ) {
    val journeyRefs = suiteResult.results.mapIndexed { index, item ->
      writeJourneyArtifact(runArtifacts, index + 1, item.resolvedJourney, item.toArtifactResult())
    }
    val error = if (suiteResult.passed) null else ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, "Journey suite failed")
    writeSummary(
      runArtifacts,
      summary(path.path, error, suiteResult.metadata ?: metadata, journeyRefs, suiteResult.results.firstOrNull()?.resolvedJourney?.journey?.platform),
    )
  }

  private suspend fun writeJourneyArtifact(
    runArtifacts: RunArtifactDirectory,
    index: Int,
    resolved: ResolvedJourney,
    result: JourneyArtifactResult,
  ): SuiteJourneyArtifact {
    val recorder = runArtifacts.journey(index, resolved.journey.name)
    writeJourneyResult(runArtifacts, recorder.resultPath, result)
    return SuiteJourneyArtifact(
      path = recorder.resultPath,
      name = resolved.journey.name,
      status = if (result.passed) ArtifactStatus.PASSED else ArtifactStatus.FAILED,
    )
  }

  /** A suite summary that passed iff [error] is null, with counts derived from [journeys]. */
  private fun summary(
    inputPath: String,
    error: ArtifactError?,
    metadata: RunArtifactMetadata?,
    journeys: List<SuiteJourneyArtifact> = emptyList(),
    platform: Platform? = null,
  ) = SuiteArtifactSummary(
    formatVersion = 1,
    timestamp = Instant.now(clock).toString(),
    inputPath = inputPath,
    status = if (error == null) ArtifactStatus.PASSED else ArtifactStatus.FAILED,
    total = journeys.size,
    passed = journeys.count { it.status == ArtifactStatus.PASSED },
    failed = journeys.count { it.status == ArtifactStatus.FAILED },
    journeys = journeys,
    error = error,
    platform = platform,
    provider = metadata?.provider,
    navigatorModel = metadata?.navigatorModel,
    inspectorModel = metadata?.inspectorModel,
    navigatorEffort = metadata?.navigatorEffort,
    inspectorEffort = metadata?.inspectorEffort,
    openaiAuth = metadata?.openaiAuth,
    modelBackend = metadata?.modelBackend,
    precheck = precheckClient?.stats?.snapshot(),
  )

  private fun ResolvedJourneyResult.toArtifactResult(): JourneyArtifactResult = JourneyArtifactResult(
    journey = JourneyArtifactIdentity(
      name = resolvedJourney.journey.name,
      file = resolvedJourney.file.path,
      app = resolvedJourney.journey.app,
      platform = resolvedJourney.journey.platform,
    ),
    passed = result.passed,
    failedAt = result.failedAt,
    error = result.setupError,
    segments = result.segments.map { segment ->
      val assertionMode = segment.assertionMode
      val assertionDescription = segment.assertionDescription
      SegmentArtifactResult(
        index = segment.index,
        passed = segment.passed,
        executionMode = segment.executionMode,
        actions = segment.actions,
        assertion = if (assertionMode != null && assertionDescription != null) {
          AssertionArtifact(description = assertionDescription, mode = assertionMode)
        } else {
          null
        },
        reasoning = segment.reasoning,
        generatedFlows = segment.generatedFlows,
        evidence = segment.evidence,
        error = segment.error,
        loop = segment.loop,
        wait = segment.wait,
      )
    },
    trail = result.trail,
  )

  private fun ResolvedJourney.toFailureArtifactResult(
    message: String,
    failedAt: Int?,
    kind: ArtifactErrorKind,
  ): JourneyArtifactResult = JourneyArtifactResult(
    journey = JourneyArtifactIdentity(
      name = journey.name,
      file = file.path,
      app = journey.app,
      platform = journey.platform,
    ),
    passed = false,
    failedAt = failedAt,
    error = ArtifactError(kind, message),
  )

  private fun printSuiteResult(suiteResult: SuiteRunResult) {
    suiteResult.results.forEachIndexed { index, journeyResult ->
      val resolved = journeyResult.resolvedJourney
      val result = journeyResult.result

      if (index > 0) echo()
      echo("File: ${resolved.file.absolutePath}")
      echo("Journey: ${resolved.journey.name}")
      echo("App: ${resolved.journey.app}")
      echo("Platform: ${resolved.journey.platform}")

      val setupError = result.setupError
      if (setupError != null) {
        echo("FAILED: Setup failed: ${setupError.message}")
      } else if (result.passed) {
        echo("PASSED: All ${result.segments.size} segments passed")
      } else {
        echo("FAILED: Segment ${result.failedAt} failed")
        result.segments.filter { !it.passed }.forEach { seg ->
          echo("  Segment ${seg.index}: ${seg.reasoning}")
        }
      }
    }

    echo()
    echo("Suite result: ${if (suiteResult.passed) "PASSED" else "FAILED"}")
    echo("Total: ${suiteResult.results.size}")
    echo("Passed: ${suiteResult.passedCount}")
    echo("Failed: ${suiteResult.failedCount}")
    precheckClient?.let { echo(it.stats.render()) }
  }

  private suspend fun runSuiteWithDevice(
    parent: Verity,
    config: VerityConfig,
    resolved: ResolvedProjectConfig,
    path: File,
    journeys: List<ResolvedJourney>,
    runArtifacts: RunArtifactDirectory,
  ): SuiteRunResult {
    val platform = journeys.first().journey.platform
    val contextDir = resolved.contextPath
    val requireContext = resolveRequiredContext(parent.requireContext, config)
    val projectContext = loadProjectContext(contextDir, requireContext) { throw CliktError(it) }

    val preflight = preflightChecker(
      parent.preflightRequest(path, platform, resolved.deviceId),
      config,
      true,
      true,
    )
    if (!preflight.report.passed) {
      throw CliktError(preflight.report.renderPlainText(), statusCode = EXIT_SETUP)
    }

    return try {
      runSuiteWithBackend(parent, resolved, journeys, runArtifacts, platform, projectContext, contextDir, requireContext, preflight)
    } finally {
      preflight.trace?.close()
    }
  }

  private suspend fun runSuiteWithBackend(
    parent: Verity,
    resolved: ResolvedProjectConfig,
    journeys: List<ResolvedJourney>,
    runArtifacts: RunArtifactDirectory,
    platform: Platform,
    projectContext: ContextBundle,
    contextDir: File?,
    requireContext: Boolean,
    preflight: CliPreflightResult,
  ): SuiteRunResult {
    val backend = backendFor(preflight)
    var primary: Throwable? = null
    var completedResults: List<ResolvedJourneyResult> = emptyList()
    val suiteResult = try {
      val provider = checkNotNull(preflight.provider)
      val navigatorModel = checkNotNull(preflight.navigatorModel)
      val inspectorModel = checkNotNull(preflight.inspectorModel)
      echo("Provider: ${provider.name}")
      echo("Navigator model: ${navigatorModel.id}")
      echo("Inspector model: ${inspectorModel.id}")
      val client = precheckConfig?.let { precheckFactory(it, preflight.trace, timer) }
      precheckClient = client
      precheckConfig?.let { echo("Pre-check: ${it.model} at ${URI(it.baseUrl).authority}") }
      projectContext.describe(contextDir, requireContext) { "  - ${it.displayPath()}" }.forEach { echo(it) }

      val session = sessionFactory(platform, preflight.deviceId ?: resolved.deviceId, resolved.disableAnimations).let { if (timing) TimingDeviceSession(it, timer) else it }
      val navigatorFactory = { navigatorAgent(parent, backend, preflight) }
      val inspectorFactory = {
        InspectorAgent(
          evaluateTreeContent = { systemPrompt, userMessage, references ->
            backend.execute(ModelRequest("tree-eval", systemPrompt, userMessage, references.mapIndexed { index, image -> LabeledLocalImage("Reference screenshot ${index + 1}", image) }, inspectorModel, inspectorSettings(preflight)))
          },
          evaluateVisualContent = { systemPrompt, userMessage, screenshotPath, references ->
            backend.execute(ModelRequest("visual-eval", systemPrompt, userMessage, listOf(LabeledLocalImage("Current screenshot", screenshotPath)) + references.mapIndexed { index, image -> LabeledLocalImage("Reference screenshot ${index + 1}", image) }, inspectorModel, inspectorSettings(preflight)))
          },
        )
      }

      session.use {
        try {
          runResolvedJourneysWithArtifacts(journeys, runArtifacts) { resolved, artifactRecorder ->
            val orchestrator = Orchestrator(
              session = session,
              navigatorFactory = navigatorFactory,
              inspectorFactory = inspectorFactory,
              context = projectContext.text,
              artifactRecorder = artifactRecorder,
              timer = timer,
              precheck = client,
            )
            orchestrator.run(resolved.journey)
          }.copy(metadata = resolved.toRunArtifactMetadata()).also { completedResults = it.results }
        } catch (e: CancellationException) {
          throw e
        } catch (e: JourneyExecutionFailure) {
          throw e
        } catch (e: Exception) {
          throw JourneyExecutionFailure(e.message ?: "Journey suite failed", e)
        }
      }
    } catch (error: Exception) {
      primary = error
      if (error !is CancellationException && error !is JourneyExecutionFailure && completedResults.isNotEmpty()) {
        throw JourneyExecutionFailure("Device session cleanup failed", error, completedResults = completedResults, kind = ArtifactErrorKind.SETUP_FAILURE)
      }
      throw error
    } finally {
      try {
        closeModelBackend(backend, primary)
      } catch (cleanup: me.chrisbanes.verity.agent.ModelBackendFailure) {
        throw JourneyExecutionFailure("Model backend cleanup failed", cleanup, completedResults = completedResults, kind = ArtifactErrorKind.SETUP_FAILURE)
      }
    }
    return suiteResult
  }
}

private class JourneyExecutionFailure(
  message: String,
  cause: Throwable,
  val resolvedJourney: ResolvedJourney? = null,
  val failedAt: Int? = null,
  val completedResults: List<ResolvedJourneyResult> = emptyList(),
  val kind: ArtifactErrorKind = when (cause) {
    is ModelFailureException -> ArtifactErrorKind.MODEL_FAILURE
    is ModelBackendFailure -> if (cause.kind == ModelBackendFailureKind.CLEANUP) ArtifactErrorKind.SETUP_FAILURE else ArtifactErrorKind.MODEL_FAILURE
    is ActionFlowPreparationException, is InvalidActionFlowException -> ArtifactErrorKind.SETUP_FAILURE
    else -> ArtifactErrorKind.JOURNEY_FAILURE
  },
) : Exception(message, cause)

internal suspend fun runResolvedJourneysWithArtifacts(
  journeys: List<ResolvedJourney>,
  runArtifacts: RunArtifactDirectory,
  runner: suspend (ResolvedJourney, JourneyRunArtifactRecorder) -> JourneyResult,
): SuiteRunResult {
  val results = mutableListOf<ResolvedJourneyResult>()
  journeys.forEachIndexed { index, resolved ->
    val result = try {
      runner(resolved, runArtifacts.journey(index + 1, resolved.journey.name))
    } catch (e: CancellationException) {
      throw e
    } catch (e: JourneyExecutionFailure) {
      throw e
    } catch (e: Exception) {
      throw JourneyExecutionFailure(e.message ?: "Journey suite failed", e, resolved, completedResults = results.toList())
    }
    results += ResolvedJourneyResult(
      resolvedJourney = resolved,
      result = result,
    )
  }
  return SuiteRunResult(results = results)
}

private fun File.displayPath(): String {
  val current = File("").absoluteFile.toPath().normalize()
  val target = absoluteFile.toPath().normalize()
  return runCatching { current.relativize(target).toString() }
    .getOrDefault(absolutePath)
}
