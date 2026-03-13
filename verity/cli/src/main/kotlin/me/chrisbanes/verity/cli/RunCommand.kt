package me.chrisbanes.verity.cli

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import java.io.File
import kotlinx.coroutines.runBlocking
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.Orchestrator
import me.chrisbanes.verity.core.context.ContextLoader
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.device.DeviceSessionFactory

class RunCommand : CliktCommand(name = "run") {
  override fun help(context: Context): String = "Execute a journey file against a connected device"

  private val journeyPath by argument("journey", help = "Path to .journey.yaml file").optional()

  override fun run() = runBlocking {
    val parent = currentContext.parent?.command as Verity

    val config = VerityConfig.loadOrDefault(File("verity/config.yaml"))
    val provider = resolveProvider(parent.provider, config)

    val apiKey = parent.apiKey ?: System.getenv(provider.envVar)
    if (provider.requiresAuth && apiKey == null) {
      error("API key required. Set ${provider.envVar} or use --api-key")
    }

    val navigatorModel = resolveModel(parent.navigatorModel, config.navigatorModel, provider.defaultNavigatorModel, provider)
    val inspectorModel = resolveModel(parent.inspectorModel, config.inspectorModel, provider.defaultInspectorModel, provider)

    val file = journeyPath?.let { File(it) }
      ?: error("Journey path required. Use: verity run <path.journey.yaml>")
    require(file.exists()) { "Journey file not found: $file" }

    val journey = JourneyLoader.fromFile(file)
    echo("Provider: ${provider.name}")
    echo("Navigator model: ${navigatorModel.id}")
    echo("Inspector model: ${inspectorModel.id}")
    echo("Running journey: ${journey.name}")
    echo("App: ${journey.app}")
    echo("Platform: ${journey.platform}")

    val session = DeviceSessionFactory.connect(
      platform = parent.platform,
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
            bundledContext = MAESTRO_CONTEXT,
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

      val result = orchestrator.run(journey)

      echo()
      if (result.passed) {
        echo("PASSED: All ${result.segments.size} segments passed")
      } else {
        echo("FAILED: Segment ${result.failedAt} failed")
        result.segments.filter { !it.passed }.forEach { seg ->
          echo("  Segment ${seg.index}: ${seg.reasoning}")
        }
      }
    }
  }

  companion object {
    private const val MAESTRO_CONTEXT =
      "Common Maestro YAML commands:\n" +
        "- launchApp: launches the app (must have appId header)\n" +
        "- tapOn: tap on element by text or id\n" +
        "- assertVisible: assert element is visible\n" +
        "- waitForAnimationToEnd: wait for animations to settle\n" +
        "- extendedWaitUntil: wait until a condition is met\n" +
        "- scrollUntilVisible: scroll until element appears\n" +
        "- inputText: type text into focused field\n" +
        "- pressKey: press a specific key (Home, Back, Enter, etc.)\n" +
        "- swipe: swipe in a direction\n" +
        "- back: press back button"
  }
}
