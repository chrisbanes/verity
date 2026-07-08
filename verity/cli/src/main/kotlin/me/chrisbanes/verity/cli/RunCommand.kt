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
import me.chrisbanes.verity.agent.JourneyArtifactRecorder
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

data class SuiteRunResult(
  val results: List<ResolvedJourneyResult>,
) {
  val passed: Boolean get() = results.all { it.result.passed }
  val passedCount: Int get() = results.count { it.result.passed }
  val failedCount: Int get() = results.count { !it.result.passed }
}

class RunCommand(
  private val loadJourney: (File, AssertionStrategy) -> Journey = JourneyLoader::fromFile,
  private val listJourneyFiles: (File) -> List<File> = JourneyLoader::listJourneyFiles,
  private val suiteRunner: (suspend (List<ResolvedJourney>) -> SuiteRunResult)? = null,
  private val dryRunSuiteRunner: (suspend (List<ResolvedJourney>, File) -> DryRunSuiteReport)? = null,
  private val clock: Clock = Clock.systemUTC(),
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

    val config = VerityConfig.loadOrDefault(File("verity/config.yaml"))
    val cliOptions = parent.projectCliOptions()
    val resolved = if (dryRun) {
      resolveDryRunProjectConfig(config, cliOptions)
    } else {
      ResolvedProjectConfig.resolve(
        config = config,
        cli = cliOptions,
      )
    }
    validateOutputDirectory(resolved.outputPath)

    val artifactWriter = RunArtifactWriter(resolved.outputPath, clock)
    val path = try {
      resolveRunJourneyFile(journeyPath, resolved.configuredJourneysPath)
    } catch (e: IllegalArgumentException) {
      val message = e.message ?: "Invalid journey path"
      val inputPath = journeyPath ?: resolved.configuredJourneysPath?.path ?: "<unresolved>"
      val runArtifacts = artifactWriter.createRun(
        suiteSlugSource = journeyPath ?: resolved.configuredJourneysPath?.path ?: "run",
      )
      writeParserFailureSummary(runArtifacts, inputPath, message)
      throw CliktError(message, statusCode = EXIT_INPUT)
    }
    val runArtifacts = artifactWriter.createRun(suiteSlugSource = path.nameWithoutExtension.ifEmpty { path.name })
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
      writeParserFailureSummary(runArtifacts, path.path, message)
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

    val suiteResult = suiteRunner?.invoke(journeys)
      ?: runSuiteWithDevice(
        parent = parent,
        config = config,
        resolved = resolved,
        path = path,
        journeys = journeys,
        runArtifacts = runArtifacts,
      )

    printSuiteResult(suiteResult)
    writeSuiteArtifacts(path, suiteResult, runArtifacts)

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

  private suspend fun writeParserFailureSummary(
    runArtifacts: RunArtifactDirectory,
    inputPath: String,
    message: String,
  ) {
    runArtifacts.writeSummary(
      SuiteArtifactSummary(
        formatVersion = 1,
        timestamp = Instant.now(clock).toString(),
        inputPath = inputPath,
        status = ArtifactStatus.FAILED,
        total = 0,
        passed = 0,
        failed = 0,
        error = ArtifactError(ArtifactErrorKind.PARSER_FAILURE, message),
      ),
    )
  }

  private suspend fun writeSuiteArtifacts(
    path: File,
    suiteResult: SuiteRunResult,
    runArtifacts: RunArtifactDirectory,
  ) {
    val journeyRefs = suiteResult.results.mapIndexed { index, item ->
      val key = "${(index + 1).toString().padStart(3, '0')}-${slugArtifactName(item.resolvedJourney.journey.name, "journey")}"
      val journeyPath = "journeys/$key.json"
      runArtifacts.writeJourneyResult(journeyPath, item.toArtifactResult())
      SuiteJourneyArtifact(
        path = journeyPath,
        name = item.resolvedJourney.journey.name,
        status = if (item.result.passed) ArtifactStatus.PASSED else ArtifactStatus.FAILED,
      )
    }
    runArtifacts.writeSummary(
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
      return runResolvedJourneysWithArtifacts(journeys, runArtifacts) { resolved, artifactRecorder ->
        val orchestrator = Orchestrator(
          session = session,
          navigatorFactory = navigatorFactory,
          inspectorFactory = inspectorFactory,
          context = projectContext.text,
          artifactRecorder = artifactRecorder,
        )
        orchestrator.run(resolved.journey)
      }
    }
  }
}

internal suspend fun runResolvedJourneysWithArtifacts(
  journeys: List<ResolvedJourney>,
  runArtifacts: RunArtifactDirectory,
  runner: suspend (ResolvedJourney, JourneyArtifactRecorder) -> JourneyResult,
): SuiteRunResult = SuiteRunResult(
  journeys.mapIndexed { index, resolved ->
    ResolvedJourneyResult(
      resolvedJourney = resolved,
      result = runner(resolved, runArtifacts.journey(index + 1, resolved.journey.name)),
    )
  },
)

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
