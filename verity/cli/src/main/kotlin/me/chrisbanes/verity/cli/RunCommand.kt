package me.chrisbanes.verity.cli

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.JourneyResult
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.Orchestrator
import me.chrisbanes.verity.core.context.ContextBundle
import me.chrisbanes.verity.core.context.ContextLoader
import me.chrisbanes.verity.core.context.ContextStatus
import me.chrisbanes.verity.core.context.ContextValidationException
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.result.ArtifactError
import me.chrisbanes.verity.core.result.ArtifactErrorKind
import me.chrisbanes.verity.core.result.ArtifactStatus
import me.chrisbanes.verity.core.result.AssertionArtifact
import me.chrisbanes.verity.core.result.JourneyArtifactIdentity
import me.chrisbanes.verity.core.result.JourneyArtifactResult
import me.chrisbanes.verity.core.result.SegmentArtifactResult
import me.chrisbanes.verity.core.result.SuiteArtifactSummary
import me.chrisbanes.verity.core.result.SuiteJourneyArtifact
import me.chrisbanes.verity.device.DeviceSessionFactory

private const val EXIT_INPUT = 2
private const val EXIT_SETUP = 3
private const val EXIT_JOURNEY = 4

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
) : CliktCommand(name = "run") {
  override fun help(context: Context): String = "Execute a journey file against a connected device"

  private val journeyPath by argument("journey", help = "Path to .journey.yaml file").optional()
  private val dryRun by option(
    "--dry-run",
    help = "Validate journeys and render generated Maestro YAML without device access",
  ).flag()

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

    if (dryRun) {
      val dryRunReport = dryRunSuiteRunner?.invoke(journeys, resolved.outputPath)
        ?: runDryRun(
          parent = parent,
          config = config,
          resolved = resolved,
          path = path,
          journeys = journeys,
        )
      echo(DryRunRenderer.renderSuite(dryRunReport))
      return@runBlocking
    }

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
          failedJourney = e.resolvedJourney ?: journeys.singleOrNull(),
          failedAt = e.failedAt,
          completedResults = e.completedResults,
        )
      } catch (artifactError: CancellationException) {
        throw artifactError
      } catch (artifactError: Exception) {
        val artifactMessage = artifactError.message ?: "Failed to write journey failure artifacts"
        writeSetupFailureSummary(runArtifacts, path.path, artifactMessage, metadata = metadata)
        throw CliktError(artifactMessage, statusCode = EXIT_SETUP)
      }
      throw CliktError(message, statusCode = EXIT_JOURNEY)
    } catch (e: Exception) {
      val message = e.message ?: "Journey suite setup failed"
      writeSetupFailureSummary(runArtifacts, path.path, message, metadata = metadata)
      throw CliktError(message, statusCode = EXIT_SETUP)
    }

    printSuiteResult(suiteResult)
    try {
      writeSuiteArtifacts(path, suiteResult, runArtifacts)
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
  }

  private fun resolveDryRunProjectConfig(
    config: VerityConfig,
    cli: ProjectCliOptions,
  ): ResolvedProjectConfig = ResolvedProjectConfig.resolve(
    config = config.copy(
      llm = config.llm?.copy(
        provider = null,
        navigatorModel = null,
        inspectorModel = null,
      ),
      provider = null,
      navigatorModel = null,
      inspectorModel = null,
    ),
    cli = cli.copy(
      provider = "ollama",
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
    val projectContext = try {
      withContext(Dispatchers.IO) {
        ContextLoader.loadProject(directory = contextDir, required = requireContext)
      }
    } catch (e: ContextValidationException) {
      throw CliktError(e.message ?: "Project context validation failed")
    }
    projectContext.describeForCli(contextDir, requireContext).forEach { echo(it) }

    var dryRunNavigator: DryRunNavigator? = null
    val planner = DryRunPlanner(
      context = projectContext.text,
      navigatorFactory = {
        dryRunNavigator ?: createDryRunNavigator(parent, config, resolved, path, journeys.first().journey.platform)
          .also { dryRunNavigator = it }
      },
    )
    val suiteReport = DryRunSuiteReport(journeys.map { resolvedJourney -> planner.plan(resolvedJourney) })
    return DryRunArtifactWriter().write(resolved.outputPath, suiteReport)
  }

  private suspend fun createDryRunNavigator(
    parent: Verity,
    config: VerityConfig,
    resolved: ResolvedProjectConfig,
    path: File,
    platform: Platform,
  ): DryRunNavigator {
    val preflight = CliPreflightChecker().check(
      request = CliPreflightRequest(
        cliProvider = parent.provider,
        cliNavigatorModel = parent.navigatorModel,
        cliInspectorModel = parent.inspectorModel,
        apiKey = parent.apiKey,
        journeyPath = path.path,
        contextPath = null,
        platform = platform,
        deviceId = resolved.deviceId,
      ),
      config = config,
      includeDevicePreflight = false,
      includeInspectorModelPreflight = false,
    )
    if (!preflight.report.passed) {
      throw CliktError(preflight.report.renderPlainText())
    }

    val provider = checkNotNull(preflight.provider)
    val navigatorModel = checkNotNull(preflight.navigatorModel)
    val executor = MultiLLMPromptExecutor(provider.createClient(preflight.apiKey.orEmpty()))
    val navigatorAgent = NavigatorAgent(
      bundledContext = if (parent.noBundledContext) "" else ContextLoader.loadBundled(),
      agentFactory = { systemPrompt ->
        AIAgent(
          promptExecutor = executor,
          llmModel = navigatorModel,
          systemPrompt = systemPrompt,
        )
      },
    )
    return DryRunNavigator { actions, appId, targetPlatform, context ->
      navigatorAgent.generate(actions, appId, targetPlatform, context)
    }
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
    writeSummary(
      runArtifacts,
      SuiteArtifactSummary(
        formatVersion = 1,
        timestamp = Instant.now(clock).toString(),
        inputPath = inputPath,
        status = ArtifactStatus.FAILED,
        total = 0,
        passed = 0,
        failed = 0,
        error = ArtifactError(ArtifactErrorKind.PARSER_FAILURE, message),
        provider = metadata?.provider,
        navigatorModel = metadata?.navigatorModel,
        inspectorModel = metadata?.inspectorModel,
      ),
    )
  }

  private suspend fun writeSetupFailureSummary(
    runArtifacts: RunArtifactDirectory,
    inputPath: String,
    message: String,
    suiteResult: SuiteRunResult? = null,
    metadata: RunArtifactMetadata? = null,
  ) {
    val summaryMetadata = suiteResult?.metadata ?: metadata
    try {
      writeSummary(
        runArtifacts,
        SuiteArtifactSummary(
          formatVersion = 1,
          timestamp = Instant.now(clock).toString(),
          inputPath = inputPath,
          status = ArtifactStatus.FAILED,
          total = suiteResult?.results?.size ?: 0,
          passed = suiteResult?.passedCount ?: 0,
          failed = suiteResult?.failedCount ?: 0,
          journeys = suiteResult?.results?.mapIndexed { index, item ->
            val recorder = runArtifacts.journey(index + 1, item.resolvedJourney.journey.name)
            SuiteJourneyArtifact(
              path = recorder.resultPath,
              name = item.resolvedJourney.journey.name,
              status = if (item.result.passed) ArtifactStatus.PASSED else ArtifactStatus.FAILED,
            )
          } ?: emptyList(),
          error = ArtifactError(ArtifactErrorKind.SETUP_FAILURE, message),
          platform = suiteResult?.results?.firstOrNull()?.resolvedJourney?.journey?.platform,
          provider = summaryMetadata?.provider,
          navigatorModel = summaryMetadata?.navigatorModel,
          inspectorModel = summaryMetadata?.inspectorModel,
        ),
      )
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
  ) {
    val completedRefs = completedResults.map { item ->
      val index = journeys.indexOf(item.resolvedJourney).takeIf { it >= 0 }?.plus(1) ?: 1
      val recorder = runArtifacts.journey(index, item.resolvedJourney.journey.name)
      writeJourneyResult(runArtifacts, recorder.resultPath, item.toArtifactResult())
      SuiteJourneyArtifact(
        path = recorder.resultPath,
        name = item.resolvedJourney.journey.name,
        status = if (item.result.passed) ArtifactStatus.PASSED else ArtifactStatus.FAILED,
      )
    }
    val failedRef = failedJourney?.let { item ->
      val index = journeys.indexOf(item).takeIf { it >= 0 }?.plus(1) ?: 1
      val recorder = runArtifacts.journey(index, item.journey.name)
      writeJourneyResult(
        runArtifacts,
        recorder.resultPath,
        item.toFailureArtifactResult(message, failedAt),
      )
      SuiteJourneyArtifact(
        path = recorder.resultPath,
        name = item.journey.name,
        status = ArtifactStatus.FAILED,
      )
    }
    val journeyRefs = completedRefs + listOfNotNull(failedRef)
    writeSummary(
      runArtifacts,
      SuiteArtifactSummary(
        formatVersion = 1,
        timestamp = Instant.now(clock).toString(),
        inputPath = inputPath,
        status = ArtifactStatus.FAILED,
        total = journeyRefs.size,
        passed = journeyRefs.count { it.status == ArtifactStatus.PASSED },
        failed = journeyRefs.count { it.status == ArtifactStatus.FAILED },
        journeys = journeyRefs,
        error = ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, message),
        platform = journeys.firstOrNull()?.journey?.platform,
        provider = metadata?.provider,
        navigatorModel = metadata?.navigatorModel,
        inspectorModel = metadata?.inspectorModel,
      ),
    )
  }

  private suspend fun writeSuiteArtifacts(
    path: File,
    suiteResult: SuiteRunResult,
    runArtifacts: RunArtifactDirectory,
  ) {
    val journeyRefs = suiteResult.results.mapIndexed { index, item ->
      val recorder = runArtifacts.journey(index + 1, item.resolvedJourney.journey.name)
      writeJourneyResult(runArtifacts, recorder.resultPath, item.toArtifactResult())
      SuiteJourneyArtifact(
        path = recorder.resultPath,
        name = item.resolvedJourney.journey.name,
        status = if (item.result.passed) ArtifactStatus.PASSED else ArtifactStatus.FAILED,
      )
    }
    writeSummary(
      runArtifacts,
      SuiteArtifactSummary(
        formatVersion = 1,
        timestamp = Instant.now(clock).toString(),
        inputPath = path.path,
        status = if (suiteResult.passed) ArtifactStatus.PASSED else ArtifactStatus.FAILED,
        total = suiteResult.results.size,
        passed = suiteResult.passedCount,
        failed = suiteResult.failedCount,
        journeys = journeyRefs,
        error = if (suiteResult.passed) {
          null
        } else {
          ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, "Journey suite failed")
        },
        platform = suiteResult.results.firstOrNull()?.resolvedJourney?.journey?.platform,
        provider = suiteResult.metadata?.provider,
        navigatorModel = suiteResult.metadata?.navigatorModel,
        inspectorModel = suiteResult.metadata?.inspectorModel,
      ),
    )
  }

  private fun ResolvedJourneyResult.toArtifactResult(): JourneyArtifactResult = JourneyArtifactResult(
    journey = JourneyArtifactIdentity(
      name = resolvedJourney.journey.name,
      file = resolvedJourney.file.path,
      app = resolvedJourney.journey.app,
      platform = resolvedJourney.journey.platform,
    ),
    passed = result.passed,
    failedAt = result.failedAt,
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
      )
    },
  )

  private fun ResolvedJourney.toFailureArtifactResult(
    message: String,
    failedAt: Int?,
  ): JourneyArtifactResult = JourneyArtifactResult(
    journey = JourneyArtifactIdentity(
      name = journey.name,
      file = file.path,
      app = journey.app,
      platform = journey.platform,
    ),
    passed = false,
    failedAt = failedAt,
    error = ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, message),
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

      if (result.passed) {
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
    val projectContext = try {
      withContext(Dispatchers.IO) {
        ContextLoader.loadProject(directory = contextDir, required = requireContext)
      }
    } catch (e: ContextValidationException) {
      throw CliktError(e.message ?: "Project context validation failed")
    }

    val preflight = CliPreflightChecker().check(
      request = CliPreflightRequest(
        cliProvider = parent.provider,
        cliNavigatorModel = parent.navigatorModel,
        cliInspectorModel = parent.inspectorModel,
        apiKey = parent.apiKey,
        journeyPath = path.path,
        contextPath = null,
        platform = platform,
        deviceId = resolved.deviceId,
      ),
      config = config,
    )
    if (!preflight.report.passed) {
      throw CliktError(preflight.report.renderPlainText())
    }

    val provider = checkNotNull(preflight.provider)
    val apiKey = preflight.apiKey.orEmpty()
    val navigatorModel = resolved.navigatorModel
    val inspectorModel = resolved.inspectorModel
    echo("Provider: ${provider.name}")
    echo("Navigator model: ${navigatorModel.id}")
    echo("Inspector model: ${inspectorModel.id}")
    projectContext.describeForCli(contextDir, requireContext).forEach { echo(it) }

    val session = DeviceSessionFactory.connect(
      platform = platform,
      deviceId = resolved.deviceId,
      disableAnimations = resolved.disableAnimations,
    )

    val executor = MultiLLMPromptExecutor(provider.createClient(apiKey))
    val navigatorFactory = {
      NavigatorAgent(
        bundledContext = if (parent.noBundledContext) "" else ContextLoader.loadBundled(),
        agentFactory = { systemPrompt ->
          AIAgent(
            promptExecutor = executor,
            llmModel = navigatorModel,
            systemPrompt = systemPrompt,
          )
        },
      )
    }
    val inspectorFactory = {
      InspectorAgent(
        treeAgentFactory = {
          AIAgent(
            promptExecutor = executor,
            llmModel = inspectorModel,
            systemPrompt = InspectorAgent.SYSTEM_PROMPT,
          )
        },
        evaluateVisualContent = { systemPrompt, userMessage, screenshotPath ->
          val p = prompt("visual-eval") {
            system(systemPrompt)
            user {
              text(userMessage)
              image(kotlinx.io.files.Path(screenshotPath.toString()))
            }
          }
          val response = executor.execute(p, inspectorModel)
          response.textContent()
        },
      )
    }

    session.use {
      return try {
        runResolvedJourneysWithArtifacts(journeys, runArtifacts) { resolved, artifactRecorder ->
          val orchestrator = Orchestrator(
            session = session,
            navigatorFactory = navigatorFactory,
            inspectorFactory = inspectorFactory,
            context = projectContext.text,
            artifactRecorder = artifactRecorder,
          )
          orchestrator.run(resolved.journey)
        }.copy(metadata = resolved.toRunArtifactMetadata())
      } catch (e: CancellationException) {
        throw e
      } catch (e: JourneyExecutionFailure) {
        throw e
      } catch (e: Exception) {
        throw JourneyExecutionFailure(e.message ?: "Journey suite failed", e)
      }
    }
  }
}

private class JourneyExecutionFailure(
  message: String,
  cause: Throwable,
  val resolvedJourney: ResolvedJourney? = null,
  val failedAt: Int? = null,
  val completedResults: List<ResolvedJourneyResult> = emptyList(),
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

private fun ContextBundle.describeForCli(contextDir: File?, required: Boolean): List<String> {
  val mode = if (required) "required" else "optional"
  return when (status) {
    ContextStatus.LOADED -> listOf("Project context: loaded ${loadedFiles.size} file(s)") +
      loadedFiles.map { "  - ${it.displayPath()}" }

    ContextStatus.NOT_CONFIGURED -> listOf("Project context: $mode, not configured")

    ContextStatus.MISSING_DIRECTORY -> listOf(
      "Project context: $mode, missing directory: ${contextDir!!.absolutePath}",
    )

    ContextStatus.EMPTY_DIRECTORY -> listOf(
      "Project context: $mode, no markdown files found in: ${contextDir!!.absolutePath}",
    )
  }
}

private fun File.displayPath(): String {
  val current = File("").absoluteFile.toPath().normalize()
  val target = absoluteFile.toPath().normalize()
  return runCatching { current.relativize(target).toString() }
    .getOrDefault(absolutePath)
}
