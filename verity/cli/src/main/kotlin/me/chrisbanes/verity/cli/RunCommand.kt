package me.chrisbanes.verity.cli

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
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

    val apiKey = parent.apiKey
      ?: error("API key required. Set ANTHROPIC_API_KEY or use --api-key")

    val file = journeyPath?.let { File(it) }
      ?: error("Journey path required. Use: verity run <path.journey.yaml>")
    require(file.exists()) { "Journey file not found: $file" }

    val journey = JourneyLoader.fromFile(file)
    echo("Running journey: ${journey.name}")
    echo("App: ${journey.app}")
    echo("Platform: ${journey.platform}")

    val session = DeviceSessionFactory.connect(
      platform = parent.platform,
      deviceId = parent.device,
      disableAnimations = parent.noAnimations,
    )

    val executor = SingleLLMPromptExecutor(AnthropicLLMClient(apiKey))

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
                llmModel = AnthropicModels.Haiku_4_5,
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
                llmModel = AnthropicModels.Sonnet_4_5,
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
              val responses = executor.execute(p, AnthropicModels.Sonnet_4_5)
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
