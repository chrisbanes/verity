package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.model.FlowResult

class InteractionExecutionFailure(val flowResult: FlowResult) : Exception("Flow execution failed: ${flowResult.output}")
