package me.chrisbanes.verity.device.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The Android device is asleep or showing a screensaver and could not be woken. */
class DeviceNotReadyException(message: String) : IllegalStateException(message)

private val wakefulness = Regex("""mWakefulness=(\w+)""")
private val focusLine = Regex("""mCurrentFocus=.*""")

// Whole class-name segments only, so an app activity like MyDreamActivity is not a screensaver.
private val dreamComponent = Regex("""[./](DreamActivity|Somnambulator)\b""")
private const val WAKE_ATTEMPTS = 3

/** The focused dream component line, or null when the focus is not a screensaver. */
private fun dreamFocus(focus: String): String? = focusLine.find(focus)?.value?.takeIf { dreamComponent.containsMatchIn(it) }

/**
 * Returns why the device is not ready for a journey, or null when it is awake or the state is unknown.
 * Unparseable or empty output is "unknown" so unusual `dumpsys` formats never block a device.
 */
internal fun parseDeviceReadiness(power: String, focus: String): String? {
  val state = wakefulness.find(power)?.groupValues?.get(1)
  if (state != null && state != "Awake") return "device is ${state.lowercase()}"
  return dreamFocus(focus)?.let { "screensaver ($it) is focused" }
}

/**
 * Wakes the device if needed, retrying up to [WAKE_ATTEMPTS] times, and throws if it stays not ready.
 * HOME is sent only while a screensaver is still focused after the wake key, so a normal foreground app is left alone.
 */
internal suspend fun ensureAwake(executeShell: (String) -> String, sleep: suspend (Long) -> Unit = { delay(it) }) {
  suspend fun shell(command: String) = withContext(Dispatchers.IO) { executeShell(command) }
  suspend fun focus() = shell("dumpsys window | grep mCurrentFocus")
  suspend fun reason() = parseDeviceReadiness(shell("dumpsys power | grep mWakefulness="), focus())

  repeat(WAKE_ATTEMPTS) {
    reason() ?: return
    shell("input keyevent KEYCODE_WAKEUP")
    sleep(1_000)
    if (dreamFocus(focus()) != null) {
      shell("input keyevent KEYCODE_HOME")
      sleep(1_000)
    }
  }
  val remaining = reason() ?: return
  throw DeviceNotReadyException(
    "Device is not ready: $remaining after $WAKE_ATTEMPTS wake attempts. " +
      "Wake the device or disable the screensaver and retry.",
  )
}

/** Runs [ensure]; on any failure closes the session without letting a close error mask the original. */
internal suspend fun ensureAwakeOrClose(ensure: suspend () -> Unit, close: () -> Unit) {
  try {
    ensure()
  } catch (e: Throwable) {
    withContext(NonCancellable) { runCatching(close).onFailure(e::addSuppressed) }
    throw e
  }
}
