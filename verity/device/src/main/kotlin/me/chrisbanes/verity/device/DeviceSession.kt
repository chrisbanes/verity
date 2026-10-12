package me.chrisbanes.verity.device

import java.nio.file.Path
import kotlin.time.Duration
import me.chrisbanes.verity.core.hierarchy.FocusDetector
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.hierarchy.containsText
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform

interface DeviceSession : AutoCloseable {
  val platform: Platform

  /** Execute a Maestro YAML flow against the device. */
  suspend fun executeFlow(yaml: String): FlowResult

  /** Validate and execute the complete structured action list without YAML parsing. */
  suspend fun executeActions(flow: ActionFlow): FlowResult

  /** Whether [pressKey] with a raw keycode is supported. */
  val supportsRawKeycodes: Boolean get() = false

  /** Whether [pressKey] with a held press is supported. */
  val supportsLongPress: Boolean get() = false

  /** Press a single key by its platform key name. */
  suspend fun pressKey(keyName: String)

  /** Preserve named short presses; long presses require platform support. */
  suspend fun pressKey(keyName: String, longPress: Boolean) {
    if (longPress) throw UnsupportedOperationException("Long key presses are not supported on $platform")
    pressKey(keyName)
  }

  /** Press a raw Android keycode; unsupported by default on other sessions. */
  suspend fun pressKey(keycode: Int, longPress: Boolean): Unit = throw UnsupportedOperationException("Raw Android keycodes are not supported on $platform")

  /** Capture the accessibility tree as a [HierarchyNode]. */
  suspend fun captureHierarchyTree(): HierarchyNode

  /** Complete capture within a positive finite budget, supported only by cooperative sessions. */
  suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode {
    require(timeout.isPositive() && timeout.isFinite()) { "Capture timeout must be positive and finite" }
    throw UnsupportedOperationException("This session does not support bounded hierarchy capture")
  }

  /** Capture and render the accessibility tree as indented text. */
  suspend fun captureHierarchy(
    filter: HierarchyFilter = HierarchyFilter.CONTENT,
  ): String {
    val tree = captureHierarchyTree()
    return HierarchyRenderer.render(tree, filter)
  }

  /** Save a screenshot to the specified path. */
  suspend fun captureScreenshot(output: Path)

  /** Complete screenshot capture within a positive finite budget, preserving earlier output on failure. */
  suspend fun captureScreenshot(output: Path, timeout: Duration) {
    require(timeout.isPositive() && timeout.isFinite()) { "Capture timeout must be positive and finite" }
    throw UnsupportedOperationException("This session does not support bounded screenshot capture")
  }

  /** Check if text appears anywhere in the accessibility tree (node-level search). */
  suspend fun containsText(text: String, ignoreCase: Boolean = true): Boolean {
    val tree = captureHierarchyTree()
    return tree.containsText(text, ignoreCase)
  }

  /** Check if text appears on or near a focused node (lenient). */
  suspend fun checkFocused(text: String): Boolean {
    val tree = captureHierarchyTree()
    return FocusDetector.containsFocused(tree, text)
  }

  /** Wait for any in-progress animations to finish. */
  suspend fun waitForAnimationToEnd()
}

/** A capture exhausted its owned complete-capture budget. */
internal class HierarchyCaptureTimeoutException : CaptureDeadlineExceededException(CaptureOperation.HIERARCHY)
