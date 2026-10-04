package me.chrisbanes.verity.cli

import com.github.ajalt.clikt.core.CliktError
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import me.chrisbanes.verity.agent.ModelFailureException
import me.chrisbanes.verity.agent.redactModelDiagnostic
import me.chrisbanes.verity.core.flow.ActionFlowYamlRenderer
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.interaction.InteractionMapper
import me.chrisbanes.verity.core.journey.JourneySegmenter
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneySegment
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.ActionFlowPreparationException
import me.chrisbanes.verity.device.validateActionFlow

fun interface DryRunNavigator {
  suspend fun generate(
    actions: List<String>,
    appId: String,
    platform: Platform,
    context: String,
  ): ActionFlow
}

enum class DryRunExecutionKind {
  FAST_PATH,
  SLOW_PATH,
}

data class DryRunSuiteReport(
  val journeys: List<DryRunJourneyReport>,
)

data class DryRunJourneyReport(
  val resolvedJourney: ResolvedJourney,
  val launchYaml: String,
  val segments: List<DryRunSegmentReport>,
  val artifactFile: File? = null,
)

data class DryRunSegmentReport(
  val index: Int,
  val actions: DryRunActionGroupReport? = null,
  val loop: DryRunLoopReport? = null,
  val assertion: DryRunAssertionReport? = null,
  val wait: DryRunWaitReport? = null,
)

data class DryRunActionGroupReport(
  val instructions: List<String>,
  val kind: DryRunExecutionKind,
  val interactions: List<String> = emptyList(),
  val yaml: String? = null,
)

data class DryRunLoopReport(
  val action: String,
  val until: String,
  val max: Int,
  val kind: DryRunExecutionKind,
  val interaction: String? = null,
  val yaml: String? = null,
  val interactions: List<String> = listOfNotNull(interaction),
)

data class DryRunWaitReport(val condition: String, val timeoutSeconds: Int)

data class DryRunAssertionReport(
  val description: String,
  val mode: AssertMode,
)

class DryRunPlanner(
  private val navigatorFactory: suspend () -> DryRunNavigator,
  private val context: String = "",
) {
  private var navigator: DryRunNavigator? = null

  suspend fun plan(resolvedJourney: ResolvedJourney): DryRunJourneyReport {
    val journey = resolvedJourney.journey
    return DryRunJourneyReport(
      resolvedJourney = resolvedJourney,
      launchYaml = launchYaml(journey),
      segments = JourneySegmenter.segment(journey.steps).map { segment -> planSegment(segment, resolvedJourney) },
    )
  }

  private suspend fun planSegment(segment: JourneySegment, resolvedJourney: ResolvedJourney): DryRunSegmentReport {
    val journey = resolvedJourney.journey
    return DryRunSegmentReport(
      index = segment.index,
      actions = planActions(segment.actions.map { it.instruction }, segment, resolvedJourney),
      loop = segment.loop?.let { loop ->
        val body = checkNotNull(planActions(loop.actionInstructions, segment, resolvedJourney))
        DryRunLoopReport(
          action = loop.action,
          until = loop.until,
          max = loop.max,
          kind = body.kind,
          interaction = body.interactions.singleOrNull(),
          interactions = body.interactions,
          yaml = body.yaml,
        )
      },
      assertion = segment.assertion?.let { DryRunAssertionReport(it.description, it.mode) },
      wait = segment.wait?.let { DryRunWaitReport(it.until, it.timeoutSeconds) },
    )
  }

  private suspend fun planActions(
    instructions: List<String>,
    segment: JourneySegment,
    resolvedJourney: ResolvedJourney,
  ): DryRunActionGroupReport? {
    if (instructions.isEmpty()) return null

    val journey = resolvedJourney.journey
    val mapper = InteractionMapper.forPlatform(journey.platform)
    val interactions = instructions.map { mapper.map(it) }
    return if (interactions.all { it != null }) {
      try {
        validateActionFlow(ActionFlow(journey.app, interactions.filterNotNull()))
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        throw generationError(resolvedJourney, segment, "Mapped action preparation failed", 3)
      }
      DryRunActionGroupReport(
        instructions = instructions,
        kind = DryRunExecutionKind.FAST_PATH,
        interactions = interactions.filterNotNull().map(::describeInteraction),
      )
    } else {
      DryRunActionGroupReport(
        instructions = instructions,
        kind = DryRunExecutionKind.SLOW_PATH,
        yaml = generateYaml(instructions, segment, resolvedJourney),
      )
    }
  }

  private suspend fun generateYaml(
    instructions: List<String>,
    segment: JourneySegment,
    resolvedJourney: ResolvedJourney,
  ): String {
    val journey = resolvedJourney.journey
    val activeNavigator = try {
      navigator()
    } catch (error: CancellationException) {
      throw error
    } catch (error: CliktError) {
      if (error.statusCode == 3) throw error
      throw generationError(resolvedJourney, segment, "Navigator setup failed", 3)
    } catch (_: Exception) {
      throw generationError(resolvedJourney, segment, "Navigator setup failed", 3)
    }
    return try {
      val selected = activeNavigator.generate(instructions, journey.app, journey.platform, context)
      validateActionFlow(selected)
      ActionFlowYamlRenderer.render(selected)
    } catch (error: CancellationException) {
      throw error
    } catch (error: ModelFailureException) {
      throw generationError(resolvedJourney, segment, error.message.orEmpty(), 5)
    } catch (error: ActionFlowPreparationException) {
      throw generationError(resolvedJourney, segment, error.message.orEmpty(), 3)
    } catch (_: Exception) {
      throw generationError(resolvedJourney, segment, "Preview generation setup failed", 3)
    }
  }

  private fun generationError(resolvedJourney: ResolvedJourney, segment: JourneySegment, message: String, status: Int): CliktError = CliktError(
    redactModelDiagnostic("Dry-run YAML generation failed for ${resolvedJourney.file.path} segment ${segment.index}: $message"),
    statusCode = status,
  )

  private suspend fun navigator(): DryRunNavigator = navigator ?: navigatorFactory().also { navigator = it }

  private fun launchYaml(journey: Journey): String = ActionFlowYamlRenderer.render(ActionFlow(journey.app, listOf(Interaction.LaunchApp())))

  internal fun describeInteraction(interaction: Interaction): String = when (interaction) {
    is Interaction.KeyPress -> "KeyPress(${describeKeyName(interaction.keyName)})"
    is Interaction.TapOnText -> "TapOnText(${interaction.text})"
    is Interaction.TapOnId -> "TapOnId(${interaction.resourceId})"
    is Interaction.Scroll -> "Scroll(${interaction.direction})"
    is Interaction.Swipe -> "Swipe(${interaction.direction})"
    Interaction.LongPressOnFocused -> "LongPressOnFocused"
    is Interaction.LongPressOnText -> "LongPressOnText(${interaction.text})"
    Interaction.PullToRefresh -> "PullToRefresh"
    is Interaction.LaunchApp -> "LaunchApp(clearState=${interaction.clearState})"
    is Interaction.InputText -> "InputText(${interaction.text})"
    Interaction.DefaultScroll -> "DefaultScroll"
    is Interaction.WaitForAnimation -> "WaitForAnimation(timeoutMs=${interaction.timeoutMs})"
    is Interaction.WaitUntilVisible -> "WaitUntilVisible(text=${interaction.text}, resourceId=${interaction.resourceId}, timeoutMs=${interaction.timeoutMs})"
  }

  private fun describeKeyName(keyName: String): String = when (keyName) {
    "Remote Dpad Down" -> "DPAD_DOWN"
    "Remote Dpad Up" -> "DPAD_UP"
    "Remote Dpad Left" -> "DPAD_LEFT"
    "Remote Dpad Right" -> "DPAD_RIGHT"
    "Remote Dpad Center" -> "DPAD_CENTER"
    else -> keyName
  }
}
