package me.chrisbanes.verity.device

import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import java.nio.file.Path

interface DeviceSession : AutoCloseable {
    val platform: Platform

    data class AnimationState(
        val windowScale: String,
        val transitionScale: String,
        val animatorScale: String,
    )

    /** Execute a Maestro YAML flow against the device. */
    suspend fun executeFlow(yaml: String): FlowResult

    /** Press a single key by its platform key name (from PlatformKeyMapper). */
    suspend fun pressKey(keyName: String)

    /** Capture the accessibility tree as a HierarchyNode. */
    suspend fun captureHierarchyTree(
        filter: HierarchyFilter = HierarchyFilter.CONTENT,
    ): HierarchyNode

    /** Capture and render the accessibility tree as indented text. */
    suspend fun captureHierarchy(
        filter: HierarchyFilter = HierarchyFilter.CONTENT,
    ): String

    /** Save a screenshot to the specified path. */
    suspend fun captureScreenshot(output: Path)

    /** Check if text appears anywhere in the accessibility tree. */
    suspend fun containsText(text: String, ignoreCase: Boolean = true): Boolean

    /** Check if text appears on or near a focused node (lenient). */
    suspend fun checkFocused(text: String): Boolean

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
