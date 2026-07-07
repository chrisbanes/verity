package me.chrisbanes.verity.cli

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.JourneyResult
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.Orchestrator
import me.chrisbanes.verity.core.context.ContextBundle
import me.chrisbanes.verity.core.context.ContextLoader
import me.chrisbanes.verity.core.context.ContextStatus
import me.chrisbanes.verity.core.context.ContextValidationException
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.model.Journey
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
  private val loadJourney: (File) -> Journey = JourneyLoader::fromFile,
  private val listJourneyFiles: (File) -> List<File> = JourneyLoader::listJourneyFiles,
  private val suiteRunner: (suspend (List<ResolvedJourney>) -> SuiteRunResult)? = null,
) : CliktCommand(name = "run") {
  override fun help(context: Context): String = "Execute a journey file against a connected device"

  private val journeyPath by argument("journey", help = "Path to .journey.yaml file").optional()

  private fun resolveJourneys(path: File): List<ResolvedJourney> {
    if (!path.exists()) {
      throw CliktError("Journey path not found: ${path.absolutePath}")
    }

    return when {
      path.isDirectory -> {
        val files = listJourneyFiles(path)
        if (files.isEmpty()) {
          throw CliktError("No journey files found in: ${path.absolutePath}")
        }
        files.map { file -> ResolvedJourney(file = file, journey = loadJourney(file)) }
          .also(::requireSinglePlatform)
      }

      path.isFile -> listOf(ResolvedJourney(file = path, journey = loadJourney(path)))

      else -> throw CliktError("Journey path is not a file or directory: ${path.absolutePath}")
    }
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

    val path = journeyPath?.let { File(it) }
      ?: throw UsageError("Journey path required. Use: verity run <path>")
    val journeys = resolveJourneys(path)

    val suiteResult = suiteRunner?.invoke(journeys)
      ?: runSuiteWithDevice(parent = parent, journeys = journeys)

    printSuiteResult(suiteResult)

    if (!suiteResult.passed) {
      throw CliktError("Journey suite failed")
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
    journeys: List<ResolvedJourney>,
  ): SuiteRunResult {
    val config = VerityConfig.loadOrDefault(File("verity/config.yaml"))
    val firstJourney = journeys.first().journey
    val platform = parent.platform ?: firstJourney.platform
    val contextDir = parent.contextPath?.let { File(it) }
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
        journeyPath = journeyPath,
        contextPath = null,
        platform = platform,
        deviceId = parent.device,
      ),
      config = config,
    )
    if (!preflight.report.passed) {
      throw CliktError(preflight.report.renderPlainText())
    }

    val provider = checkNotNull(preflight.provider)
    val apiKey = preflight.apiKey.orEmpty()
    val navigatorModel = checkNotNull(preflight.navigatorModel)
    val inspectorModel = checkNotNull(preflight.inspectorModel)
    echo("Provider: ${provider.name}")
    echo("Navigator model: ${navigatorModel.id}")
    echo("Inspector model: ${inspectorModel.id}")
    projectContext.describeForCli(contextDir, requireContext).forEach { echo(it) }

    val session = DeviceSessionFactory.connect(
      platform = platform,
      deviceId = parent.device,
      disableAnimations = parent.noAnimations,
    )

    val executor = SingleLLMPromptExecutor(provider.createClient(apiKey))

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
              val responses = executor.execute(p, inspectorModel)
              responses.last().content
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
