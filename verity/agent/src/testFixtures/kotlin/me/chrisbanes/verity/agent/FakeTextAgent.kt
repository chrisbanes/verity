package me.chrisbanes.verity.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Test double for [AIAgent] that delegates to a lambda responder.
 * Shared across modules via testFixtures.
 */
class FakeTextAgent(
  private val responder: suspend (String) -> String,
) : AIAgent<String, String>() {
  override val id: String = "fake-agent"
  override val agentConfig: AIAgentConfig = AIAgentConfig(
    prompt = Prompt.Empty,
    model = LLModel(provider = LLMProvider.OpenAI, id = "fake"),
    maxAgentIterations = 1,
  )

  override suspend fun run(agentInput: String, sessionId: String?): String = responder(agentInput)
  override fun createSession(sessionId: String?) = error("FakeTextAgent does not support sessions")
  override suspend fun close() = Unit
}
