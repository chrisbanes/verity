package me.chrisbanes.verity.agent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.core.result.FocusNodeArtifact
import me.chrisbanes.verity.core.result.JourneyTrailArtifact
import me.chrisbanes.verity.core.result.TrailEntryArtifact
import me.chrisbanes.verity.core.result.TrailGranularity
import me.chrisbanes.verity.core.result.TrailOrigin
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FocusCaptureResult
import me.chrisbanes.verity.device.FocusChangeObserver

/** Where in the journey an execution happened. [iteration] is the zero-based loop body. */
internal data class TrailSource(
  val segment: Int,
  val origin: TrailOrigin = TrailOrigin.ACTIONS,
  val iteration: Int? = null,
)

/**
 * Bounded memory of one journey: recent verdicts, an execution trail and at most two earlier
 * screenshots. Owned by a single `Orchestrator.run`, used serially, and closed when the run ends.
 * Focus capture is advisory: any failure or timeout records unknown focus and never alters execution.
 */
internal class JourneyMemory(
  private val session: DeviceSession,
  private val focusObserver: FocusChangeObserver = FocusChangeObserver(),
  private val tempRoot: Path? = null,
) {
  private class Verdict(val subject: String, val passed: Boolean, val reasoning: String)

  private val trail = ArrayDeque<TrailEntryArtifact>()
  private var droppedEntries = 0
  private val verdicts = ArrayDeque<Verdict>()
  private var droppedVerdicts = 0
  private var directory: Path? = null
  private var hasFirst = false
  private var hasLatest = false

  /** Runs [block] once, appending one entry with focus observed before and after the whole call. */
  suspend fun <T> recordExecution(
    source: TrailSource,
    granularity: TrailGranularity,
    instructions: List<String>,
    outcome: (T) -> Boolean = { true },
    block: suspend () -> T,
  ): T {
    val before = captureFocus()
    val result = try {
      block()
    } catch (e: InteractionExecutionFailure) {
      append(source, granularity, instructions, false, before, captureFocus())
      throw e
    }
    append(source, granularity, instructions, outcome(result), before, captureFocus())
    return result
  }

  fun recordVerdict(subject: String, passed: Boolean, reasoning: String) {
    verdicts.addLast(Verdict(clip(subject).first, passed, clip(reasoning).first))
    while (verdicts.size > MAX_VERDICTS) {
      verdicts.removeFirst()
      droppedVerdicts++
    }
  }

  /** Copies [path] now, because callers reuse or delete their file after inspection. */
  suspend fun recordScreenshot(path: Path) {
    withContext(Dispatchers.IO) {
      var temp: Path? = null
      try {
        val dir = directory ?: Files.createTempDirectory(tempRoot ?: Path.of(System.getProperty("java.io.tmpdir")), "verity-journey-memory-").also { directory = it }
        temp = Files.createTempFile(dir, "copy-", ".png")
        Files.copy(path, temp, StandardCopyOption.REPLACE_EXISTING)
        Files.move(temp, dir.resolve(if (hasFirst) LATEST else FIRST), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        if (hasFirst) hasLatest = true else hasFirst = true
      } catch (_: IOException) {
        // The previous reference, if any, stays in place.
        temp?.let { Files.deleteIfExists(it) }
      } catch (_: SecurityException) {
        // Same: remembering a screenshot is advisory.
        temp?.let { Files.deleteIfExists(it) }
      }
    }
  }

  /**
   * Empty when there is nothing to say, so a fresh journey sends no reference context.
   * Tree inspections pass `includeScreenshots = false` and receive text only.
   */
  fun inspectionContext(includeScreenshots: Boolean): InspectionContext {
    val dir = directory
    val screenshots = if (dir == null || !includeScreenshots) emptyList() else listOfNotNull(dir.resolve(FIRST).takeIf { hasFirst }, dir.resolve(LATEST).takeIf { hasLatest })
    if (verdicts.isEmpty() && trail.isEmpty() && screenshots.isEmpty()) return InspectionContext()
    return InspectionContext(render(screenshots.size), screenshots)
  }

  fun trail(): JourneyTrailArtifact = JourneyTrailArtifact(trail.toList(), droppedEntries, MAX_TRAIL_ENTRIES, MAX_TEXT_CHARS, MAX_FOCUSED_NODES)

  suspend fun close() {
    withContext(NonCancellable + Dispatchers.IO) {
      directory?.toFile()?.deleteRecursively()
      directory = null
    }
  }

  private suspend fun captureFocus(): List<FocusNodeArtifact>? = when (val captured = focusObserver.capture(session, FOCUS_CAPTURE_TIMEOUT)) {
    is FocusCaptureResult.Captured -> captured.observation.focusedNodes.map { FocusNodeArtifact(it.path, it.resourceId) }
    FocusCaptureResult.TimedOut, is FocusCaptureResult.Failed -> null
  }

  private fun append(
    source: TrailSource,
    granularity: TrailGranularity,
    instructions: List<String>,
    succeeded: Boolean,
    before: List<FocusNodeArtifact>?,
    after: List<FocusNodeArtifact>?,
  ) {
    var truncated = false
    val clipped = instructions.map { text -> clip(text).also { truncated = truncated || it.second }.first }
    fun focus(nodes: List<FocusNodeArtifact>?): List<FocusNodeArtifact>? {
      if (nodes == null) return null
      if (nodes.size > MAX_FOCUSED_NODES) truncated = true
      return nodes.take(MAX_FOCUSED_NODES).map { node ->
        val id = node.resourceId?.let { clip(it).also { clipped -> truncated = truncated || clipped.second }.first }
        node.copy(resourceId = id)
      }
    }
    val focusBefore = focus(before)
    val focusAfter = focus(after)
    trail.addLast(TrailEntryArtifact(source.segment, granularity, source.origin, source.iteration, clipped, succeeded, focusBefore, focusAfter, truncated))
    while (trail.size > MAX_TRAIL_ENTRIES) {
      trail.removeFirst()
      droppedEntries++
    }
  }

  private fun render(screenshotCount: Int): String = buildString {
    appendLine("Journey memory for this journey only. It is a reference, not proof that the current assertion or condition passes; judge the current state yourself.")
    if (verdicts.isNotEmpty()) {
      appendLine("Earlier verdicts, oldest first${omitted(droppedVerdicts)}:")
      verdicts.forEach { appendLine("- [${if (it.passed) "passed" else "failed"}] ${it.subject}: ${it.reasoning}") }
    }
    if (trail.isNotEmpty()) {
      appendLine("Execution trail, oldest first${omitted(droppedEntries)}. Focus is observed only before and after each entry; a flow is one device call with no intermediate focus:")
      trail.forEach { appendLine("- ${describe(it)}") }
    }
    when (screenshotCount) {
      1 -> appendLine("Reference screenshot 1 is the only earlier screenshot of this journey. It is not the current screenshot.")
      2 -> appendLine("Reference screenshot 1 is the first earlier screenshot of this journey; Reference screenshot 2 is the most recent earlier screenshot. Neither is the current screenshot.")
    }
  }.trim()

  private fun omitted(count: Int) = if (count > 0) " ($count older omitted)" else ""

  private fun describe(entry: TrailEntryArtifact): String {
    val origin = entry.origin.name.lowercase().replace('_', '-') + (entry.iteration?.let { " #$it" } ?: "")
    val outcome = if (entry.succeeded) "succeeded" else "failed"
    val instructions = entry.instructions.joinToString("; ") { "\"$it\"" }
    return "segment ${entry.segment}, ${entry.granularity.name.lowercase()} ($origin), $outcome: $instructions; " +
      "focus before: ${focusText(entry.focusBefore)}; after: ${focusText(entry.focusAfter)}" +
      if (entry.truncated) " [truncated]" else ""
  }

  private fun focusText(nodes: List<FocusNodeArtifact>?): String = when {
    nodes == null -> "unknown"
    nodes.isEmpty() -> "none"
    else -> nodes.joinToString(", ") { it.resourceId?.let { id -> "$id (${it.path})" } ?: it.path }
  }

  /** Cuts to [MAX_TEXT_CHARS] including a trailing ellipsis; the flag reports whether it cut. */
  private fun clip(text: String): Pair<String, Boolean> = if (text.length <= MAX_TEXT_CHARS) text to false else (text.take(MAX_TEXT_CHARS - 1) + "…") to true

  companion object {
    const val MAX_TRAIL_ENTRIES = 20
    const val MAX_VERDICTS = 10
    const val MAX_TEXT_CHARS = 300
    const val MAX_FOCUSED_NODES = 5
    val FOCUS_CAPTURE_TIMEOUT = 2.seconds
    internal const val FIRST = "first.png"
    internal const val LATEST = "latest.png"
  }
}
