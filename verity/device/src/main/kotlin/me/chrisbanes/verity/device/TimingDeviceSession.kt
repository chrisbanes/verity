package me.chrisbanes.verity.device

import java.nio.file.Path
import kotlin.time.Duration
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.timing.PhaseKind
import me.chrisbanes.verity.core.timing.PhaseTimer

/**
 * Times every suspend call. The default-bodied members call the delegate's own
 * [captureHierarchyTree], never this wrapper, so each is overridden to be recorded once.
 */
class TimingDeviceSession(
  private val delegate: DeviceSession,
  private val timer: PhaseTimer,
) : DeviceSession by delegate {
  private suspend fun <T> timed(name: String, block: suspend () -> T): T = timer.phase(PhaseKind.DEVICE, name, block)

  override suspend fun executeFlow(yaml: String): FlowResult = timed("executeFlow") { delegate.executeFlow(yaml) }

  override suspend fun executeActions(flow: ActionFlow): FlowResult = timed("executeActions") { delegate.executeActions(flow) }

  override suspend fun pressKey(keyName: String) = timed("pressKey") { delegate.pressKey(keyName) }

  override suspend fun pressKey(keyName: String, longPress: Boolean) = timed("pressKey") { delegate.pressKey(keyName, longPress) }

  override suspend fun pressKey(keycode: Int, longPress: Boolean) = timed("pressKey") { delegate.pressKey(keycode, longPress) }

  override suspend fun captureHierarchyTree(): HierarchyNode = timed("captureHierarchyTree") { delegate.captureHierarchyTree() }

  override suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode = timed("captureHierarchyTree") { delegate.captureHierarchyTree(timeout) }

  override suspend fun captureHierarchy(filter: HierarchyFilter): String = timed("captureHierarchy") { delegate.captureHierarchy(filter) }

  override suspend fun captureScreenshot(output: Path) = timed("captureScreenshot") { delegate.captureScreenshot(output) }

  override suspend fun captureScreenshot(output: Path, timeout: Duration) = timed("captureScreenshot") { delegate.captureScreenshot(output, timeout) }

  override suspend fun containsText(text: String, ignoreCase: Boolean): Boolean = timed("containsText") { delegate.containsText(text, ignoreCase) }

  override suspend fun checkFocused(text: String): Boolean = timed("checkFocused") { delegate.checkFocused(text) }

  override suspend fun waitForAnimationToEnd() = timed("waitForAnimationToEnd") { delegate.waitForAnimationToEnd() }
}
