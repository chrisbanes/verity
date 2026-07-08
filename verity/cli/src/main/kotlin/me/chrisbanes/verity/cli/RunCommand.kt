package me.chrisbanes.verity.cli

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
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
import me.chrisbanes.verity.device.DeviceSessionFactory

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

    val path = try {
      resolveRunJourneyFile(journeyPath, resolved.configuredJourneysPath)
    } catch (e: IllegalArgumentException) {
      throw UsageError(e.message ?: "Invalid journey path")
    }
    val journeys = resolveJourneys(
      path = path,
      assertionStrategy = resolved.assertionStrategy,
      platformOverride = resolved.platform,
    )

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
      )

    printSuiteResult(suiteResult)

    if (!suiteResult.passed) {
      throw CliktError("Journey suite failed")
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

    session.use {
      val orchestrator = Orchestrator(
        session = session,
        navigatorFactory = {
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
        },
        inspectorFactory = {
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
        },
        context = projectContext.text,
      )

      return SuiteRunResult(
        journeys.map { resolved ->
          ResolvedJourneyResult(
            resolvedJourney = resolved,
            result = orchestrator.run(resolved.journey),
          )
        },
      )
    }
  }
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
