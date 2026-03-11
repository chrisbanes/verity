package me.chrisbanes.verity.device.android

import dadb.Dadb
import maestro.KeyCode
import maestro.Maestro
import me.chrisbanes.verity.core.hierarchy.FocusDetector
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import java.nio.file.Path

/**
 * Android device session backed by Dadb (ADB over TCP) and Maestro SDK (gRPC).
 *
 * [dadb] handles ADB shell commands. [maestro] handles UI automation via
 * the on-device gRPC agent (hierarchy, screenshots, key presses, animations).
 */
class AndroidDeviceSession(
    private val dadb: Dadb,
    private val maestro: Maestro,
    override val platform: Platform,
) : DeviceSession {

    override suspend fun executeFlow(yaml: String): FlowResult {
        // Orchestra and YamlCommandReader are in a separate maestro-orchestra module
        // not included in maestro-client. Flow execution requires that dependency.
        TODO("Wire Maestro SDK flow execution — requires maestro-orchestra dependency")
    }

    override suspend fun pressKey(keyName: String) {
        val keyCode = KeyCode.valueOf(keyName)
        maestro.pressKey(keyCode)
    }

    override suspend fun captureHierarchyTree(filter: HierarchyFilter): HierarchyNode {
        val hierarchy = maestro.viewHierarchy(false)
        return MaestroTreeConverter.convert(hierarchy.root)
    }

    override suspend fun captureHierarchy(filter: HierarchyFilter): String {
        val tree = captureHierarchyTree(filter)
        return HierarchyRenderer.render(tree, filter)
    }

    @Suppress("DEPRECATION")
    override suspend fun captureScreenshot(output: Path) {
        maestro.takeScreenshot(output.toFile(), false)
    }

    override suspend fun containsText(text: String, ignoreCase: Boolean): Boolean {
        val hierarchy = captureHierarchy(HierarchyFilter.CONTENT)
        return if (ignoreCase) {
            hierarchy.lowercase().contains(text.lowercase())
        } else {
            hierarchy.contains(text)
        }
    }

    override suspend fun checkFocused(text: String): Boolean {
        val hierarchy = captureHierarchy(HierarchyFilter.FOCUS)
        return FocusDetector.containsFocused(hierarchy, text)
    }

    override suspend fun shell(command: String): String {
        val response = dadb.shell(command)
        return response.output
    }

    override suspend fun waitForAnimationToEnd() {
        maestro.waitForAnimationToEnd(null)
    }

    override suspend fun getAnimationState(): DeviceSession.AnimationState {
        return DeviceSession.AnimationState(
            windowScale = dadb.shell("settings get global window_animation_scale").output.trim(),
            transitionScale = dadb.shell("settings get global transition_animation_scale").output.trim(),
            animatorScale = dadb.shell("settings get global animator_duration_scale").output.trim(),
        )
    }

    override suspend fun disableAnimations() {
        dadb.shell("settings put global window_animation_scale 0")
        dadb.shell("settings put global transition_animation_scale 0")
        dadb.shell("settings put global animator_duration_scale 0")
    }

    override suspend fun restoreAnimationState(state: DeviceSession.AnimationState) {
        dadb.shell("settings put global window_animation_scale ${state.windowScale}")
        dadb.shell("settings put global transition_animation_scale ${state.transitionScale}")
        dadb.shell("settings put global animator_duration_scale ${state.animatorScale}")
    }

    override fun close() {
        maestro.close()
        dadb.close()
    }
}
