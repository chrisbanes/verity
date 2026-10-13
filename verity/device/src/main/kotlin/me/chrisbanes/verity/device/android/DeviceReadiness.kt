package me.chrisbanes.verity.device.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The Android device is asleep or showing a screensaver and could not be woken. */
class DeviceNotReadyException(message: String) : IllegalStateException(message)

private val wakefulness = Regex("""mWakefulness=(\w+)""")
private val focusLine = Regex("""mCurrentFocus=.*""")

// Whole class-name segments only, so an app activity like MyDreamActivity is not a screensaver.
private val dreamComponent = Regex("""[./](DreamActivity|Somnambulator)\b""")
private const val WAKE_ATTEMPTS = 3

/**
 * Returns why the device is not ready for a journey, or null when it is awake or the state is unknown.
 * Unparseable or empty output is "unknown" so unusual `dumpsys` formats never block a device.
 */
internal fun parseDeviceReadiness(power: String, focus: String): String? {
  val state = wakefulness.find(power)?.groupValues?.get(1)
  if (state != null && state != "Awake") return "device is ${state.lowercase()}"
  val focused = focusLine.find(focus)?.value
  if (focused != null && dreamComponent.containsMatchIn(focused)) return "screensaver ($focused) is focused"
  return null
}

/** Wakes the device if needed, retrying up to [WAKE_ATTEMPTS] times, and throws if it stays not ready. */
internal suspend fun ensureAwake(executeShell: (String) -> String, sleep: suspend (Long) -> Unit = { delay(it) }) {
  suspend fun reason() = withContext(Dispatchers.IO) {
    parseDeviceReadiness(
      power = executeShell("dumpsys power | grep mWakefulness="),
      focus = executeShell("dumpsys window | grep mCurrentFocus"),
    )
  }

  repeat(WAKE_ATTEMPTS) {
    reason() ?: return
    withContext(Dispatchers.IO) {
      executeShell("input keyevent KEYCODE_WAKEUP")
      executeShell("cmd dreams stop-dreaming")
    }
    sleep(1_000)
  }
  val remaining = reason() ?: return
  throw DeviceNotReadyException(
    "Device is not ready: $remaining after $WAKE_ATTEMPTS wake attempts. " +
      "Wake the device or disable the screensaver and retry.",
  )
}
