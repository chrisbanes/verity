package me.chrisbanes.verity.device.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.device.prepareMaestroActions

private val packageName = Regex("""[A-Za-z0-9_.]+""")

// `$` allows nested activity classes; the component is single-quoted so the device shell doesn't expand it.
private val componentName = Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+""")

/**
 * Runs [flow], launching apps that only declare a `LEANBACK_LAUNCHER` activity through the shell,
 * because Maestro's `launchApp` only resolves `LAUNCHER`. Every other flow goes to [runMaestro] unchanged.
 */
internal suspend fun executeWithLeanbackLaunch(
  flow: ActionFlow,
  executeShell: (String) -> String,
  runMaestro: suspend (ActionFlow) -> FlowResult,
): FlowResult {
  if (flow.actions.none { it is Interaction.LaunchApp }) return runMaestro(flow)
  val appId = flow.appId
  // Reject invalid flows before any shell effect, as Maestro execution would.
  prepareMaestroActions(flow)
  // appId comes from journey YAML and is interpolated into shell commands.
  if (!packageName.matches(appId)) return FlowResult(false, "Invalid Android package name: '$appId'")
  // `resolve-activity --brief` prints a header line then `pkg/.Activity`, or "No activity found".
  suspend fun resolve(category: String) = withContext(Dispatchers.IO) {
    executeShell("cmd package resolve-activity --brief --user current -a android.intent.action.MAIN -c android.intent.category.$category $appId")
  }
  // Only an explicit "not found" takes the shell path; unrecognised output leaves Maestro's launch alone.
  if (!resolve("LAUNCHER").contains("No activity found")) return runMaestro(flow)
  val leanback = resolve("LEANBACK_LAUNCHER").lineSequence().map(String::trim).lastOrNull { componentName.matches(it) }
    ?: return FlowResult(false, "No LAUNCHER or LEANBACK_LAUNCHER activity found for '$appId'. Is the app installed?")

  // Keep action order: Maestro runs the actions between shell launches.
  val pending = mutableListOf<Interaction>()
  suspend fun flushPending(): FlowResult? {
    if (pending.isEmpty()) return null
    val result = runMaestro(ActionFlow(appId, pending.toList()))
    pending.clear()
    return result.takeUnless { it.success }
  }
  for (action in flow.actions) {
    if (action !is Interaction.LaunchApp) {
      pending += action
      continue
    }
    flushPending()?.let { return it }
    // Require affirmative output: `pm clear` prints "Success" and `am start -W` prints "Status: ok".
    // `am force-stop` prints nothing and exits 0 even for protected packages, so only a reported error is detectable.
    val failure = withContext(Dispatchers.IO) {
      val stopped = executeShell("am force-stop $appId")
      if (stopped.contains("Exception") || stopped.contains("Error")) return@withContext "am force-stop $appId failed: ${stopped.trim()}"
      if (action.clearState == true) {
        // pm clear defaults to the system user; am start targets the current user.
        val cleared = executeShell("pm clear --user current $appId")
        if (!cleared.contains("Success")) return@withContext "pm clear $appId failed: ${cleared.trim()}"
      }
      val started = executeShell("am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n '$leanback'")
      if (!started.contains("Status: ok")) "am start -n $leanback failed: ${started.trim()}" else null
    }
    if (failure != null) return FlowResult(false, failure)
  }
  return flushPending() ?: FlowResult(true)
}
