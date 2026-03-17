package me.chrisbanes.verity.device.android

import dadb.Dadb
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

  override suspend fun executeFlow(yaml: String): FlowResult = executeMaestroFlow(maestro, yaml)

  override suspend fun pressKey(keyName: String) = withContext(Dispatchers.IO) {
    val keyCode = KeyCode.valueOf(keyName)
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
    val response = dadb.shell(command)
    response.output
  }

  override suspend fun waitForAnimationToEnd(): Unit = withContext(Dispatchers.IO) {
    maestro.waitForAnimationToEnd(null)
  }

  override suspend fun getAnimationState(): DeviceSession.AnimationState = withContext(Dispatchers.IO) {
    DeviceSession.AnimationState(
      windowScale = dadb.shell("settings get global window_animation_scale").output.trim(),
      transitionScale = dadb.shell("settings get global transition_animation_scale").output.trim(),
      animatorScale = dadb.shell("settings get global animator_duration_scale").output.trim(),
    )
  }

  override suspend fun disableAnimations(): Unit = withContext(Dispatchers.IO) {
    dadb.shell("settings put global window_animation_scale 0")
    dadb.shell("settings put global transition_animation_scale 0")
    dadb.shell("settings put global animator_duration_scale 0")
  }

  override suspend fun restoreAnimationState(state: DeviceSession.AnimationState): Unit = withContext(Dispatchers.IO) {
    dadb.shell("settings put global window_animation_scale ${state.windowScale}")
    dadb.shell("settings put global transition_animation_scale ${state.transitionScale}")
    dadb.shell("settings put global animator_duration_scale ${state.animatorScale}")
  }

  override fun close() {
    maestro.close()
    dadb.close()
  }
}
