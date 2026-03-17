package me.chrisbanes.verity.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfigBase
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Test double for [AIAgent] that delegates to a lambda responder.
 * Shared across modules via testFixtures.
 */
class FakeTextAgent(
  private val responder: suspend (String) -> String,
) : AIAgent<String, String> {
  override val id: String = "fake-agent"
  override val agentConfig: AIAgentConfigBase = object : AIAgentConfigBase {
    override val prompt: Prompt = Prompt.Empty
    override val model: LLModel = LLModel(provider = LLMProvider.OpenAI, id = "fake")
  }

  override suspend fun getState(): AIAgent.Companion.State<String> = AIAgent.Companion.State.NotStarted()
  override suspend fun run(agentInput: String): String = responder(agentInput)
  override suspend fun close() = Unit
}
