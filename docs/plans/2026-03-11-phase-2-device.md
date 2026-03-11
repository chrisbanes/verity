# Phase 2: `:verity:device` — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the device abstraction layer with the `DeviceSession` interface and platform-specific implementations for Android (Dadb + Maestro gRPC) and iOS (Maestro XCTest HTTP).

**Architecture:** A single `DeviceSession` interface defines all device operations. `AndroidDeviceSession` wraps Dadb for ADB communication and Maestro's `AndroidDriver` for gRPC-based UI automation. `IosDeviceSession` wraps Maestro's XCTest HTTP client. `DeviceSessionFactory` handles connection, auto-discovery, and animation management.

**Tech Stack:** Maestro SDK (`dev.mobile:maestro-client`), Dadb (`dev.mobile:dadb`), Maestro iOS driver (`dev.mobile:maestro-ios-driver`), grpc-netty-shaded

**Design doc:** `docs/plans/2026-03-11-verity-design.md`

**Prerequisite:** Phase 0 and Phase 1 complete.

**Important:** This module depends on the Maestro SDK, which is not well-documented for embedded use. You may need to explore the Maestro source code to confirm exact class names and method signatures. The key classes to look for are: `Maestro`, `MaestroDriver`, `AndroidDriver`, `Orchestra`, `MaestroFlowParser`, `ViewHierarchy`, `TreeNode`.

**Delivery mode (required):**
- **Scaffold milestone:** It is acceptable to land structure with temporary `TODO()` stubs while SDK surfaces are being validated.
- **Production-ready milestone:** No `TODO()` remains in `:verity:device` production sources. Android and iOS paths are both wired against real SDK types and run through at least one device-backed smoke check.

### Task 0: SDK validation spike (must happen first)

**Goal:** remove class/method signature uncertainty before writing production code.

**Steps:**
1. Verify Maestro Android APIs used by this phase (`Maestro`, `AndroidDriver`, flow parser/orchestra classes, hierarchy capture, screenshot, wait-for-animation).
2. Verify Maestro iOS APIs (`IOSDriver`, XCTest installer/client, hierarchy/screenshot APIs).
3. Write a short compatibility note (in this file) listing any naming or API differences from assumptions in Tasks 2–4.
4. Update the code snippets in this plan before implementation if mismatches are found.

**Compatibility notes (validated against maestro-client 2.3.0, maestro-ios-driver 2.3.0, dadb 1.2.10):**

- `TreeNode`: API matches plan — `attributes: Map<String, String>`, `children: List<TreeNode>`, `focused/selected/checked/enabled/clickable: Boolean?`
- `Maestro(driver: Driver)`: constructor takes `maestro.Driver`. Key methods: `pressKey(KeyCode)` (enum, not String), `viewHierarchy(boolean): TreeNode` (value class return), `takeScreenshot(File, boolean)`, `waitForAnimationToEnd(Long?)`, `close()`
- `AndroidDriver(dadb: Dadb, hostPort: Int?, emulatorName: String?, reinstallDriver: Boolean, metricsProvider: Metrics)`: wraps Dadb + gRPC
- `IOSDriver(iosDevice: device.IOSDevice, insights: Insights, metricsProvider: Metrics)`: wraps `device.IOSDevice` interface (in `maestro-ios-driver` jar)
- iOS hierarchy uses `hierarchy.AXElement` with fields: `label`, `elementType`, `identifier`, `selected`, `hasFocus`, `value`, `frame`, `enabled`, `title`, `children`
- `Dadb.discover()` / `Dadb.create(host, port)`: static companion methods. `shell(): AdbShellResponse` with `.output` property
- **Orchestra/YamlCommandReader NOT in maestro-client** — flow execution must be stubbed for scaffold milestone
- `device.IOSDevice` interface (in ios-driver jar): `viewHierarchy(boolean): hierarchy.ViewHierarchy`
- `device.SimctlIOSDevice` is the concrete simulator implementation

---

### Task 1: DeviceSession interface

**Files:**
- Create: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/DeviceSession.kt`

**Step 1: Write the interface**

```kotlin
package me.chrisbanes.verity.device

import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import java.io.File

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
    suspend fun captureHierarchyTree(filter: HierarchyFilter = HierarchyFilter.CONTENT): HierarchyNode

    /** Capture and render the accessibility tree as indented text. */
    suspend fun captureHierarchy(filter: HierarchyFilter = HierarchyFilter.CONTENT): String

    /** Save a screenshot to the specified file. */
    suspend fun captureScreenshot(outputFile: File)

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
    suspend fun saveAnimationState(): AnimationState? = null
    suspend fun disableAnimations() = Unit
    suspend fun restoreAnimationState(state: AnimationState) = Unit
}
```

**Step 2: Commit**

```bash
git add verity/device/src/
git commit -m "feat(device): add DeviceSession interface"
```

---

### Task 2: AndroidDeviceSession

This is the most complex class. It wraps Dadb + Maestro SDK.

**Files:**
- Create: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/android/AndroidDeviceSession.kt`
- Create: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/android/MaestroTreeConverter.kt`

**Step 1: Write MaestroTreeConverter**

Converts Maestro's `TreeNode` (or `ViewHierarchy`) into our `HierarchyNode`.

```kotlin
package me.chrisbanes.verity.device.android

import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import maestro.TreeNode

/**
 * Converts Maestro's TreeNode into our platform-agnostic HierarchyNode.
 *
 * NOTE: Verify the exact TreeNode API against the Maestro SDK source.
 * Key fields expected: attributes (Map<String, String>), children (List<TreeNode>),
 * and boolean properties like focused, selected, clickable, enabled, checked.
 */
object MaestroTreeConverter {

    fun convert(node: TreeNode): HierarchyNode {
        val attributes = node.attributes
            .filterValues { it.isNotEmpty() }

        val states = buildSet {
            if (node.focused == true) add("focused")
            if (node.selected == true) add("selected")
            if (node.checked == true) add("checked")
            if (node.enabled == false) add("disabled")
            if (node.clickable == true) add("clickable")
        }

        return HierarchyNode(
            attributes = attributes,
            states = states,
            children = node.children.map { convert(it) },
        )
    }
}
```

**Note:** The exact `TreeNode` property names (`focused`, `selected`, `clickable`, `attributes`) must be verified against the Maestro SDK. Check `maestro.TreeNode` in the SDK source.

**Step 2: Write AndroidDeviceSession**

```kotlin
package me.chrisbanes.verity.device.android

import me.chrisbanes.verity.core.hierarchy.FocusDetector
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import dadb.Dadb
import java.io.File

/**
 * Android device session backed by Dadb (ADB over TCP) and Maestro SDK (gRPC).
 *
 * NOTE: This implementation references Maestro SDK classes whose exact APIs
 * should be verified against the SDK source code. Key classes:
 * - maestro.Maestro — main entry, provides viewHierarchy(), pressKey(), etc.
 * - maestro.drivers.AndroidDriver — connects via gRPC to the on-device agent
 * - maestro.orchestra.Orchestra — executes parsed flows
 * - maestro.orchestra.yaml.YamlFlowParser — parses YAML into MaestroCommand lists
 */
class AndroidDeviceSession(
    private val dadb: Dadb,
    private val maestro: Any, // Replace with actual Maestro type
    override val platform: Platform,
) : DeviceSession {

    override suspend fun executeFlow(yaml: String): FlowResult {
        // 1. Parse YAML with MaestroFlowParser
        // 2. Execute commands through Orchestra
        // 3. Return FlowResult(success, output)
        TODO("Wire Maestro SDK flow execution — parse YAML, execute via Orchestra")
    }

    override suspend fun pressKey(keyName: String) {
        // maestro.pressKey(keyName) or equivalent
        TODO("Wire Maestro SDK key press")
    }

    override suspend fun captureHierarchyTree(filter: HierarchyFilter): HierarchyNode {
        // val viewHierarchy = maestro.viewHierarchy()
        // return MaestroTreeConverter.convert(viewHierarchy.root)
        TODO("Wire Maestro SDK hierarchy capture")
    }

    override suspend fun captureHierarchy(filter: HierarchyFilter): String {
        val tree = captureHierarchyTree(filter)
        return HierarchyRenderer.render(tree, filter)
    }

    override suspend fun captureScreenshot(outputFile: File) {
        // maestro.takeScreenshot(outputFile) or dadb equivalent
        TODO("Wire Maestro SDK or ADB screenshot")
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
        // maestro.waitForAnimationToEnd() or equivalent
        TODO("Wire Maestro SDK animation wait")
    }

    override fun close() {
        // maestro.close()
        // dadb.close()
        TODO("Close Maestro and Dadb connections")
    }
}
```

**Step 3: Commit**

```bash
git add verity/device/src/
git commit -m "feat(device): add AndroidDeviceSession with Maestro SDK structure"
```

---

### Task 3: IosDeviceSession

**Files:**
- Create: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/ios/IosDeviceSession.kt`
- Create: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/ios/XcTestTreeConverter.kt`

**Step 1: Write IosDeviceSession**

```kotlin
package me.chrisbanes.verity.device.ios

import me.chrisbanes.verity.core.hierarchy.FocusDetector
import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.HierarchyRenderer
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import java.io.File

/**
 * iOS device session backed by Maestro's XCTest HTTP client.
 *
 * The XCTest runner exposes an HTTP server on the device/simulator at port 22087.
 * Maestro's iOS driver communicates via JSON POST requests.
 *
 * Key classes to reference from Maestro SDK:
 * - maestro.drivers.IOSDriver
 * - XCTestDriverClient (HTTP client for the on-device XCTest server)
 * - LocalXCTestInstaller (manages XCTest runner lifecycle)
 */
class IosDeviceSession(
    private val iosDriver: Any, // Replace with actual Maestro IOSDriver type
) : DeviceSession {

    override val platform: Platform = Platform.IOS

    override suspend fun executeFlow(yaml: String): FlowResult {
        TODO("Wire Maestro iOS flow execution")
    }

    override suspend fun pressKey(keyName: String) {
        TODO("Wire iOS key press / gesture")
    }

    override suspend fun captureHierarchyTree(filter: HierarchyFilter): HierarchyNode {
        // val hierarchy = iosDriver.viewHierarchy()
        // return XcTestTreeConverter.convert(hierarchy)
        TODO("Wire Maestro iOS hierarchy capture")
    }

    override suspend fun captureHierarchy(filter: HierarchyFilter): String {
        val tree = captureHierarchyTree(filter)
        return HierarchyRenderer.render(tree, filter)
    }

    override suspend fun captureScreenshot(outputFile: File) {
        TODO("Wire Maestro iOS screenshot")
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
        TODO("Wire iOS shell command (xcrun simctl or devicectl)")
    }

    override suspend fun waitForAnimationToEnd() {
        TODO("Wire Maestro iOS animation wait")
    }

    override fun close() {
        TODO("Close iOS driver")
    }
}
```

**Step 2: Write XcTestTreeConverter placeholder**

```kotlin
package me.chrisbanes.verity.device.ios

import me.chrisbanes.verity.core.hierarchy.HierarchyNode

/**
 * Converts Maestro's iOS view hierarchy into our platform-agnostic HierarchyNode.
 *
 * NOTE: Verify the iOS hierarchy format from Maestro's XCTest driver.
 * The structure should be similar to Android's TreeNode but with iOS-specific
 * accessibility attributes (label, value, identifier, traits).
 */
object XcTestTreeConverter {

    fun convert(node: Any): HierarchyNode {
        TODO("Implement iOS tree conversion — examine Maestro IOSDriver.viewHierarchy() return type")
    }
}
```

**Step 3: Commit**

```bash
git add verity/device/src/
git commit -m "feat(device): add IosDeviceSession with XCTest HTTP structure"
```

---

### Task 4: DeviceSessionFactory

**Files:**
- Create: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/DeviceSessionFactory.kt`

**Step 1: Write the factory**

```kotlin
package me.chrisbanes.verity.device

import me.chrisbanes.verity.core.model.Platform

/**
 * Creates DeviceSession instances with auto-discovery and animation management.
 *
 * When disableAnimations=true:
 * - Saves current animation scale values
 * - Sets window_animation_scale, transition_animation_scale,
 *   animator_duration_scale to 0
 * - Restores original values when session is closed
 */
object DeviceSessionFactory {
    suspend fun connect(
        platform: Platform,
        deviceId: String? = null,
        disableAnimations: Boolean = false,
    ): DeviceSession {
        return when (platform) {
            Platform.ANDROID_TV, Platform.ANDROID_MOBILE -> connectAndroid(platform, deviceId, disableAnimations)
            Platform.IOS -> connectIos(deviceId, disableAnimations)
        }
    }

    private suspend fun connectAndroid(
        platform: Platform,
        deviceId: String?,
        disableAnimations: Boolean,
    ): DeviceSession {
        // 1. Connect via Dadb
        //    val dadb = if (deviceId != null) Dadb.connect(deviceId) else Dadb.discover()
        // 2. Create AndroidDriver → Maestro instance
        // 3. Return AndroidDeviceSession(dadb, maestro, platform)
        // 4. If disableAnimations:
        //      val state = session.saveAnimationState()
        //      session.disableAnimations()
        //      return a wrapper that restores on close using session.restoreAnimationState(state)
        TODO("Wire Dadb connection and Maestro initialization")
    }

    private suspend fun connectIos(
        deviceId: String?,
        disableAnimations: Boolean,
    ): DeviceSession {
        // 1. Discover or connect to iOS simulator/device
        // 2. Install XCTest runner if needed
        // 3. Create IOSDriver
        // 4. Return IosDeviceSession(iosDriver)
        TODO("Wire Maestro iOS driver initialization")
    }
}
```

**Step 2: Commit**

```bash
git add verity/device/src/
git commit -m "feat(device): add DeviceSessionFactory with auto-discovery and animation management"
```

---

### Task 5: Unit tests for non-SDK logic

The device layer has limited unit testability since most logic requires a real device. Focus tests on the tree converters and the shared logic that doesn't need SDK connections.

**Files:**
- Test: `verity/device/src/test/kotlin/me/chrisbanes/verity/device/DeviceSessionFactoryTest.kt`

**Step 1: Write basic structure tests**

```kotlin
package me.chrisbanes.verity.device

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class DeviceSessionFactoryTest {
    @Test
    fun `animation state preserves values`() {
        val state = DeviceSession.AnimationState("1.0", "1.0", "1.0")
        assertThat(state.windowScale).isEqualTo("1.0")
        assertThat(state.transitionScale).isEqualTo("1.0")
        assertThat(state.animatorScale).isEqualTo("1.0")
    }
}
```

**Step 2: Run test**

Run: `./gradlew :verity:device:test`
Expected: PASS

**Step 3: Commit**

```bash
git add verity/device/src/test/
git commit -m "test(device): add basic DeviceSessionFactory tests"
```

---

## Verification

After all tasks, `:verity:device` should contain:

```
verity/device/src/main/kotlin/me/chrisbanes/verity/device/
├── DeviceSession.kt
├── DeviceSessionFactory.kt
├── android/
│   ├── AndroidDeviceSession.kt
│   └── MaestroTreeConverter.kt
└── ios/
    ├── IosDeviceSession.kt
    └── XcTestTreeConverter.kt
```

For the **scaffold milestone**, it is acceptable for SDK-interaction sections to remain stubbed temporarily.

For the **production-ready milestone** (required before downstream phases depend on device behavior), all `TODO()` stubs in production code must be removed and both Android+iOS paths must be verified against real SDK APIs.

**Definition of Done (production-ready):**
- No `TODO()` in `verity/device/src/main/kotlin/**`.
- `./gradlew :verity:device:test` passes.
- At least one Android and one iOS smoke command path has been executed and documented.
