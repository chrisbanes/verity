package me.chrisbanes.verity.device.android

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import maestro.KeyCode
import maestro.Maestro
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.executeMaestroFlow

/**
 * Android device session backed by Maestro SDK (ADB and gRPC).
 *
 * [executeShell] handles ADB shell commands through Maestro's device connection. [maestro]
 * handles UI automation via the on-device gRPC agent (hierarchy, screenshots, key presses,
 * animations) and owns the connection lifecycle.
 */
class AndroidDeviceSession(
  private val maestro: Maestro,
  override val platform: Platform,
  private val executeShell: (String) -> String,
) : DeviceSession {

  override suspend fun executeFlow(yaml: String): FlowResult = executeMaestroFlow(maestro, yaml)

  override suspend fun pressKey(keyName: String) = withContext(Dispatchers.IO) {
    val keyCode = checkNotNull(KeyCode.Companion.getByName(keyName)) {
      "Unknown key name: '$keyName'"
    }
    maestro.pressKey(keyCode)
  }

  override suspend fun captureHierarchyTree(): HierarchyNode = withContext(Dispatchers.IO) {
    val hierarchy = maestro.viewHierarchy(false)
    MaestroTreeConverter.convert(hierarchy.root)
  }

  @Suppress("DEPRECATION")
  override suspend fun captureScreenshot(output: Path): Unit = withContext(Dispatchers.IO) {
    maestro.takeScreenshot(output.toFile(), false)
  }

  override suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
    executeShell(command)
  }

  override suspend fun waitForAnimationToEnd(): Unit = withContext(Dispatchers.IO) {
    maestro.waitForAnimationToEnd(null)
  }

  override suspend fun getAnimationState(): DeviceSession.AnimationState = withContext(Dispatchers.IO) {
    DeviceSession.AnimationState(
      windowScale = executeShell("settings get global window_animation_scale").trim(),
      transitionScale = executeShell("settings get global transition_animation_scale").trim(),
      animatorScale = executeShell("settings get global animator_duration_scale").trim(),
    )
  }

  override suspend fun disableAnimations(): Unit = withContext(Dispatchers.IO) {
    executeShell("settings put global window_animation_scale 0")
    executeShell("settings put global transition_animation_scale 0")
    executeShell("settings put global animator_duration_scale 0")
  }

  override suspend fun restoreAnimationState(state: DeviceSession.AnimationState): Unit = withContext(Dispatchers.IO) {
    executeShell("settings put global window_animation_scale ${state.windowScale}")
    executeShell("settings put global transition_animation_scale ${state.transitionScale}")
    executeShell("settings put global animator_duration_scale ${state.animatorScale}")
  }

  override fun close() {
    maestro.close()
  }
}
