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
import kotlinx.coroutines.runBlocking
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.JourneyResult
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.Orchestrator
import me.chrisbanes.verity.core.context.ContextLoader
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
    val provider = resolveProvider(parent.provider, config)

    val apiKey = parent.apiKey ?: System.getenv(provider.envVar)
    if (provider.requiresAuth && apiKey == null) {
      throw UsageError("API key required. Set ${provider.envVar} or use --api-key")
    }

    val navigatorModel = resolveModel(parent.navigatorModel, config.navigatorModel, provider.defaultNavigatorModel, provider)
    val inspectorModel = resolveModel(parent.inspectorModel, config.inspectorModel, provider.defaultInspectorModel, provider)

    echo("Provider: ${provider.name}")
    echo("Navigator model: ${navigatorModel.id}")
    echo("Inspector model: ${inspectorModel.id}")

    val firstJourney = journeys.first().journey
    val platform = parent.platform ?: firstJourney.platform
    val session = DeviceSessionFactory.connect(
      platform = platform,
      deviceId = parent.device,
      disableAnimations = parent.noAnimations,
    )

    val executor = SingleLLMPromptExecutor(provider.createClient(apiKey ?: ""))

    session.use {
      val injectedContext = parent.contextPath?.let { ContextLoader.load(File(it)) } ?: ""

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
        context = injectedContext,
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
