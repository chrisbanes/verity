package me.chrisbanes.verity.cli

import com.github.ajalt.clikt.core.CliktError
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.interaction.InteractionMapper
import me.chrisbanes.verity.core.journey.JourneySegmenter
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneySegment
import me.chrisbanes.verity.core.model.Platform

fun interface DryRunNavigator {
  suspend fun generate(
    actions: List<String>,
    appId: String,
    platform: Platform,
    context: String,
  ): String
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
)

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
        val mapper = InteractionMapper.forPlatform(journey.platform)
        val interaction = mapper.map(loop.action)
        if (interaction != null) {
          DryRunLoopReport(
            action = loop.action,
            until = loop.until,
            max = loop.max,
            kind = DryRunExecutionKind.FAST_PATH,
            interaction = describeInteraction(interaction),
          )
        } else {
          DryRunLoopReport(
            action = loop.action,
            until = loop.until,
            max = loop.max,
            kind = DryRunExecutionKind.SLOW_PATH,
            yaml = generateYaml(listOf(loop.action), segment, resolvedJourney),
          )
        }
      },
      assertion = segment.assertion?.let { DryRunAssertionReport(it.description, it.mode) },
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
    return try {
      navigator().generate(instructions, journey.app, journey.platform, context)
    } catch (e: CancellationException) {
      throw e
    } catch (e: CliktError) {
      if (e.message.hasDryRunContext(resolvedJourney, segment)) {
        throw e
      }
      throw generationError(resolvedJourney, segment, e.message)
    } catch (e: Exception) {
      throw generationError(resolvedJourney, segment, e.message)
    }
  }

  private fun generationError(resolvedJourney: ResolvedJourney, segment: JourneySegment, message: String?): CliktError = CliktError(
    "Dry-run YAML generation failed for ${resolvedJourney.file.path} segment ${segment.index}: " +
      (message ?: "unknown error"),
  )

  private fun String?.hasDryRunContext(resolvedJourney: ResolvedJourney, segment: JourneySegment): Boolean = this?.contains(resolvedJourney.file.path) == true && contains("segment ${segment.index}")

  private suspend fun navigator(): DryRunNavigator = navigator ?: navigatorFactory().also { navigator = it }

  private fun launchYaml(journey: Journey): String = "appId: ${journey.app}\n---\n- launchApp"

  private fun describeInteraction(interaction: Interaction): String = when (interaction) {
    is Interaction.KeyPress -> "KeyPress(${describeKeyName(interaction.keyName)})"
    is Interaction.TapOnText -> "TapOnText(${interaction.text})"
    is Interaction.TapOnId -> "TapOnId(${interaction.resourceId})"
    is Interaction.Scroll -> "Scroll(${interaction.direction})"
    is Interaction.Swipe -> "Swipe(${interaction.direction})"
    Interaction.LongPressOnFocused -> "LongPressOnFocused"
    is Interaction.LongPressOnText -> "LongPressOnText(${interaction.text})"
    Interaction.PullToRefresh -> "PullToRefresh"
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
