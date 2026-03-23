package me.chrisbanes.verity.device

import java.nio.file.Path
import me.chrisbanes.verity.core.hierarchy.FocusDetector
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.hierarchy.containsText
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform

interface DeviceSession : AutoCloseable {
  val platform: Platform

  data class AnimationState(
    val windowScale: String,
    val transitionScale: String,
    val animatorScale: String,
  ) {
    init {
      validateScale(windowScale, "window animation")
      validateScale(transitionScale, "transition animation")
      validateScale(animatorScale, "animator duration")
    }

    private companion object {
      val SCALE_PATTERN = Regex("""\d+(\.\d+)?""")

      /** Android animation scale bounds: 0.0 (off) to 10.0 (10x speed) */
      private const val MIN_SCALE = 0.0
      private const val MAX_SCALE = 10.0

      fun validateScale(value: String, name: String) {
        require(value.matches(SCALE_PATTERN)) { "Invalid $name scale: $value" }
        val numeric = value.toDouble()
        require(numeric in MIN_SCALE..MAX_SCALE) {
          "$name scale $value out of bounds. Expected: $MIN_SCALE to $MAX_SCALE"
        }
      }
    }
  }

  /** Execute a Maestro YAML flow against the device. */
  suspend fun executeFlow(yaml: String): FlowResult

  /** Press a single key by its platform key name. */
  suspend fun pressKey(keyName: String)

  /** Capture the accessibility tree as a [HierarchyNode]. */
  suspend fun captureHierarchyTree(): HierarchyNode

  /** Capture and render the accessibility tree as indented text. */
  suspend fun captureHierarchy(
    filter: HierarchyFilter = HierarchyFilter.CONTENT,
  ): String {
    val tree = captureHierarchyTree()
    return HierarchyRenderer.render(tree, filter)
  }

  /** Save a screenshot to the specified path. */
  suspend fun captureScreenshot(output: Path)

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

  /** Execute a shell command on the device. */
  suspend fun shell(command: String): String

  /** Wait for any in-progress animations to finish. */
  suspend fun waitForAnimationToEnd()

  /**
   * Android-only animation controls.
   *
   * Default implementations are no-op for platforms that don't expose
   * Android global animation scale settings.
   */
  suspend fun getAnimationState(): AnimationState? = null

  suspend fun disableAnimations() = Unit

  suspend fun restoreAnimationState(state: AnimationState) = Unit
}
