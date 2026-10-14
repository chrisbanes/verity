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

  override val supportsRawKeycodes: Boolean get() = true

  override val supportsLongPress: Boolean get() = true

  override suspend fun executeFlow(yaml: String): FlowResult = executeMaestroFlow(maestro, yaml, onCommandStart)

  override suspend fun executeActions(flow: ActionFlow): FlowResult = executeWithLeanbackLaunch(flow, executeShell) {
    executeMaestroActions(maestro, it, onCommandStart = onCommandStart)
  }

  override suspend fun pressKey(keyName: String) = pressKey(keyName, false)

  override suspend fun pressKey(keyName: String, longPress: Boolean) {
    val key = checkNotNull(KeyCode.Companion.getByName(keyName)) { "Unknown key name: '$keyName'" }
    pressKey(androidKeycode(key), longPress)
  }

  override suspend fun pressKey(keycode: Int, longPress: Boolean) {
    require(keycode >= 0) { "Android keycode must be non-negative" }
    withContext(Dispatchers.IO) {
      executeShell("input keyevent ${if (longPress) "--longpress " else ""}$keycode")
    }
  }

  // Preserve Maestro 2.11.0 AndroidDriver's closed mapping for named presses.
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

  override suspend fun waitForAnimationToEnd(): Unit = withContext(Dispatchers.IO) {
    maestro.waitForAnimationToEnd(null)
  }

  /** Wakes the device and fails with [DeviceNotReadyException] if it stays asleep or dreaming. */
  suspend fun ensureAwake() = ensureAwake(executeShell)

  suspend fun getAnimationState(): AnimationState = withContext(Dispatchers.IO) {
    AnimationState(
      windowScale = executeShell("settings get global window_animation_scale").trim(),
      transitionScale = executeShell("settings get global transition_animation_scale").trim(),
      animatorScale = executeShell("settings get global animator_duration_scale").trim(),
    )
  }

  suspend fun disableAnimations(): Unit = withContext(Dispatchers.IO) {
    executeShell("settings put global window_animation_scale 0")
    executeShell("settings put global transition_animation_scale 0")
    executeShell("settings put global animator_duration_scale 0")
  }

  suspend fun restoreAnimationState(state: AnimationState): Unit = withContext(Dispatchers.IO) {
    restoreScale("window_animation_scale", state.windowScale)
    restoreScale("transition_animation_scale", state.transitionScale)
    restoreScale("animator_duration_scale", state.animatorScale)
  }

  private fun restoreScale(setting: String, value: String) {
    executeShell(
      if (value == AnimationState.UNSET) "settings delete global $setting" else "settings put global $setting $value",
    )
  }

  override fun close() {
    maestro.close()
  }
}

/**
 * Android global animation scales, validated so they are safe to interpolate into shell commands.
 * A scale the device has never set reads back as [UNSET].
 */
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

  companion object {
    /** What `settings get` prints for a setting that has never been written. */
    const val UNSET = "null"

    private val SCALE_PATTERN = Regex("""\d+(\.\d+)?""")

    /** Android animation scale bounds: 0.0 (off) to 10.0 (10x speed) */
    private const val MIN_SCALE = 0.0
    private const val MAX_SCALE = 10.0

    private fun validateScale(value: String, name: String) {
      if (value == UNSET) return
      require(value.matches(SCALE_PATTERN)) { "Invalid $name scale: $value" }
      val numeric = value.toDouble()
      require(numeric in MIN_SCALE..MAX_SCALE) {
        "$name scale $value out of bounds. Expected: $MIN_SCALE to $MAX_SCALE"
      }
    }
  }
}
