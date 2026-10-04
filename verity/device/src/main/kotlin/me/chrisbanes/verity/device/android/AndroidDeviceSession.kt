package me.chrisbanes.verity.device.android

import java.nio.file.Path
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import maestro.KeyCode
import maestro.Maestro
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.HierarchyCaptureTimeoutException
import me.chrisbanes.verity.device.captureBoundedScreenshot
import me.chrisbanes.verity.device.executeMaestroActions
import me.chrisbanes.verity.device.executeMaestroFlow

/**
 * Android device session backed by Maestro SDK (ADB and gRPC).
 *
 * [executeShell] handles ADB shell commands through Maestro's device connection. [maestro]
 * handles UI automation via the on-device gRPC agent (hierarchy, screenshots, key presses,
 * animations) and owns the connection lifecycle.
 */
class AndroidDeviceSession(
  internal val maestro: Maestro,
  override val platform: Platform,
  private val onCommandStart: ((Int) -> Unit)?,
  private val executeShell: (String) -> String,
) : DeviceSession {
  constructor(maestro: Maestro, platform: Platform, executeShell: (String) -> String) :
    this(maestro, platform, null, executeShell)

  override suspend fun executeFlow(yaml: String): FlowResult = executeMaestroFlow(maestro, yaml, onCommandStart)

  override suspend fun executeActions(flow: ActionFlow): FlowResult = executeMaestroActions(maestro, flow, onCommandStart = onCommandStart)

  override suspend fun pressKey(keyName: String) = withContext(Dispatchers.IO) {
    val keyCode = checkNotNull(KeyCode.Companion.getByName(keyName)) {
      "Unknown key name: '$keyName'"
    }
    maestro.pressKey(keyCode)
  }

  override suspend fun pressKey(keyName: String, longPress: Boolean) {
    if (!longPress) {
      pressKey(keyName)
      return
    }
    val key = checkNotNull(KeyCode.Companion.getByName(keyName)) { "Unknown key name: '$keyName'" }
    pressKey(androidKeycode(key), true)
  }

  override suspend fun pressKey(keycode: Int, longPress: Boolean) {
    require(keycode >= 0) { "Android keycode must be non-negative" }
    withContext(Dispatchers.IO) {
      executeShell("input keyevent ${if (longPress) "--longpress " else ""}$keycode")
    }
  }

  // Preserve Maestro 2.11.0 AndroidDriver's closed mapping for named holds.
  private fun androidKeycode(key: KeyCode): Int = when (key) {
    KeyCode.ENTER -> 66
    KeyCode.BACKSPACE -> 67
    KeyCode.BACK -> 4
    KeyCode.HOME -> 3
    KeyCode.LOCK -> 276
    KeyCode.VOLUME_UP -> 24
    KeyCode.VOLUME_DOWN -> 25
    KeyCode.REMOTE_UP -> 19
    KeyCode.REMOTE_DOWN -> 20
    KeyCode.REMOTE_LEFT -> 21
    KeyCode.REMOTE_RIGHT -> 22
    KeyCode.REMOTE_CENTER -> 23
    KeyCode.REMOTE_PLAY_PAUSE -> 85
    KeyCode.REMOTE_STOP -> 86
    KeyCode.REMOTE_NEXT -> 87
    KeyCode.REMOTE_PREVIOUS -> 88
    KeyCode.REMOTE_REWIND -> 89
    KeyCode.REMOTE_FAST_FORWARD -> 90
    KeyCode.ESCAPE -> 111
    KeyCode.POWER -> 26
    KeyCode.TAB -> 62
    KeyCode.REMOTE_SYSTEM_NAVIGATION_UP -> 280
    KeyCode.REMOTE_SYSTEM_NAVIGATION_DOWN -> 281
    KeyCode.REMOTE_BUTTON_A -> 96
    KeyCode.REMOTE_BUTTON_B -> 97
    KeyCode.REMOTE_MENU -> 82
    KeyCode.TV_INPUT -> 178
    KeyCode.TV_INPUT_HDMI_1 -> 243
    KeyCode.TV_INPUT_HDMI_2 -> 244
    KeyCode.TV_INPUT_HDMI_3 -> 245
  }

  override suspend fun captureHierarchyTree(): HierarchyNode = withContext(Dispatchers.IO) {
    val hierarchy = maestro.viewHierarchy(false)
    MaestroTreeConverter.convert(hierarchy.root)
  }

  override suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode {
    require(timeout.isPositive() && timeout.isFinite()) { "Capture timeout must be positive and finite" }
    val started = System.nanoTime()
    val parent = currentCoroutineContext()
    val tree = try {
      withTimeoutOrNull(timeout) {
        withContext(Dispatchers.IO) {
          val context = currentCoroutineContext()
          fun checkpoint() {
            context.ensureActive()
            if (System.nanoTime() - started >= timeout.inWholeNanoseconds) throw HierarchyCaptureTimeoutException()
          }
          checkpoint()
          val hierarchy = try {
            maestro.viewHierarchy(false)
          } catch (failure: Throwable) {
            checkpoint()
            throw failure
          }
          checkpoint()
          MaestroTreeConverter.convert(hierarchy.root, ::checkpoint).also { checkpoint() }
        }
      }
    } catch (failure: Throwable) {
      parent.ensureActive()
      throw failure
    }
    parent.ensureActive()
    if (System.nanoTime() - started >= timeout.inWholeNanoseconds) throw HierarchyCaptureTimeoutException()
    return tree ?: throw HierarchyCaptureTimeoutException()
  }

  @Suppress("DEPRECATION")
  override suspend fun captureScreenshot(output: Path): Unit = withContext(Dispatchers.IO) {
    maestro.takeScreenshot(output.toFile(), false)
  }

  override suspend fun captureScreenshot(output: Path, timeout: Duration) {
    captureBoundedScreenshot(output, timeout) { sink, checkpoint, _ ->
      checkpoint()
      // The public uncropped SDK route owns its interruptible acquisition and closes the sink.
      try {
        maestro.takeScreenshot(sink, false)
      } catch (failure: Throwable) {
        checkpoint()
        throw failure
      }
      checkpoint()
    }
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
