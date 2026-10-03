package me.chrisbanes.verity.device

import kotlin.coroutines.cancellation.CancellationException
import maestro.KeyCode
import maestro.SwipeDirection
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.WaitForAnimationToEndCommand
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.ActionFlowInvalidReason
import me.chrisbanes.verity.core.model.InvalidActionFlowException

/** Validates SDK-supported actions without a device, filesystem, or parser. */
fun validateActionFlow(flow: ActionFlow) {
  prepareMaestroActions(flow)
}

internal object MaestroActionCompiler {
  fun compile(flow: ActionFlow): List<MaestroCommand> {
    flow.validate()
    return listOf(MaestroCommand(ApplyConfigurationCommand(MaestroConfig(appId = flow.appId)))) +
      flow.actions.map { action ->
        MaestroCommand(
          when (action) {
            is Interaction.LaunchApp -> LaunchAppCommand(appId = flow.appId, clearState = action.clearState)

            is Interaction.KeyPress -> PressKeyCommand(
              code = KeyCode.getByName(action.keyName)
                ?: throw InvalidActionFlowException(ActionFlowInvalidReason.UNSUPPORTED_KEY),
            )

            is Interaction.TapOnText -> TapOnElementCommand(selector = ElementSelector(textRegex = action.text), retryIfNoChange = false, waitUntilVisible = false, longPress = false)

            is Interaction.TapOnId -> TapOnElementCommand(selector = ElementSelector(idRegex = action.resourceId), retryIfNoChange = false, waitUntilVisible = false, longPress = false)

            is Interaction.LongPressOnText -> TapOnElementCommand(selector = ElementSelector(textRegex = action.text), retryIfNoChange = false, waitUntilVisible = false, longPress = true)

            Interaction.LongPressOnFocused -> TapOnElementCommand(selector = ElementSelector(focused = true), retryIfNoChange = false, waitUntilVisible = false, longPress = true)

            is Interaction.Scroll -> SwipeCommand(direction = SwipeDirection.valueOf(action.direction.name))

            is Interaction.Swipe -> SwipeCommand(direction = SwipeDirection.valueOf(action.direction.name))

            Interaction.PullToRefresh -> SwipeCommand(direction = SwipeDirection.UP)

            Interaction.DefaultScroll -> ScrollCommand()

            is Interaction.InputText -> InputTextCommand(text = action.text)

            is Interaction.WaitForAnimation -> WaitForAnimationToEndCommand(timeout = action.timeoutMs?.toString())

            is Interaction.WaitUntilVisible -> AssertConditionCommand(
              condition = Condition(visible = ElementSelector(textRegex = action.text, idRegex = action.resourceId)),
              timeout = action.timeoutMs.toString(),
            )
          },
        )
      }
  }
}

internal fun prepareMaestroActions(
  flow: ActionFlow,
  compile: (ActionFlow) -> List<MaestroCommand> = MaestroActionCompiler::compile,
): List<MaestroCommand> = try {
  compile(flow)
} catch (error: CancellationException) {
  throw error
} catch (error: InvalidActionFlowException) {
  throw error
} catch (_: Exception) {
  throw ActionFlowPreparationException()
}

/** Local preparation failed before command execution; exposes no input or cause. */
class ActionFlowPreparationException(
  val phase: ActionFlowPreparationPhase = ActionFlowPreparationPhase.COMMAND_COMPILATION,
) : Exception("Action flow preparation failed: ${phase.diagnostic}")

enum class ActionFlowPreparationPhase(val diagnostic: String) {
  COMMAND_COMPILATION("command compilation"),
  RUNNER_INITIALIZATION("runner initialization"),
}
