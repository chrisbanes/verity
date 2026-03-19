# iOS XCTestIOSDevice Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Switch iOS device backend from the stubbed `SimctlIOSDevice` to the fully implemented `XCTestIOSDevice`/`LocalIOSDevice`, enabling iOS smoke tests.

**Architecture:** Replace `SimctlIOSDevice` in `DeviceSessionFactory.connectIos()` with Maestro's `LocalIOSDevice`, which delegates UI automation to `XCTestIOSDevice` (HTTP client on port 22087) and app lifecycle to `SimctlIOSDevice`. This is the same composite pattern Maestro CLI uses internally. Enable the `@Disabled` iOS smoke test and add a CI workflow job for it.

**Tech Stack:** Maestro 2.3.0 (`maestro-ios`, `maestro-ios-driver`), XCTest HTTP driver, GitHub Actions macOS runner

---

### Task 1: Add `maestro-ios` dependency to device module

We import from `maestro-ios` directly (for `LocalIOSDevice`, `XCTestIOSDevice`), so declare it explicitly even though it's already transitive.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `verity/device/build.gradle.kts`

**Step 1: Add library entry to version catalog**

In `gradle/libs.versions.toml`, add after the `maestro-ios-driver` line:

```toml
maestro-ios = { module = "dev.mobile:maestro-ios", version.ref = "maestro-ios" }
```

**Step 2: Add dependency to device module**

In `verity/device/build.gradle.kts`, add after the `maestro-ios-driver` line:

```kotlin
implementation(libs.maestro.ios)
```

**Step 3: Verify compilation**

Run: `./gradlew :verity:device:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
feat: add explicit maestro-ios dependency for XCTestIOSDevice
```

---

### Task 2: Switch `DeviceSessionFactory.connectIos()` to use `LocalIOSDevice`

**Files:**
- Modify: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/DeviceSessionFactory.kt`

**Step 1: Write the new `connectIos()` implementation**

Replace the `connectIos` method (lines 100-109) with:

```kotlin
private suspend fun connectIos(deviceId: String?): DeviceSession {
  val resolvedId = resolveIosDeviceId(deviceId)
  val simctlDevice = SimctlIOSDevice(resolvedId)

  val installer = LocalXCTestInstaller(
    deviceId = resolvedId,
    deviceType = IOSDeviceType.SIMULATOR,
    defaultPort = 22087,
    iOSDriverConfig = LocalXCTestInstaller.IOSDriverConfig(
      prebuiltRunner = true,
      sourceDirectory = "",
      context = Context.CLI,
      snapshotKeyHonorModalViews = null,
    ),
    deviceController = simctlDevice,
  )
  val driverClient = XCTestDriverClient(installer)
  val xcTestDevice = XCTestIOSDevice(
    deviceId = resolvedId,
    client = driverClient,
    getInstalledApps = { emptySet() },
  )
  val iosDevice = LocalIOSDevice(
    deviceId = resolvedId,
    xcTestDevice = xcTestDevice,
    deviceController = simctlDevice,
  )

  val driver = IOSDriver(iosDevice)
  val maestro = Maestro.ios(driver, openDriver = true)
  return IosDeviceSession(maestro, iosDevice)
}
```

**Step 2: Add imports**

Add to the import block:

```kotlin
import ios.LocalIOSDevice
import ios.xctest.XCTestIOSDevice
import util.IOSDeviceType
import xcuitest.XCTestDriverClient
import xcuitest.installer.Context
import xcuitest.installer.LocalXCTestInstaller
```

**Step 3: Verify compilation**

Run: `./gradlew :verity:device:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Run existing unit tests**

Run: `./gradlew :verity:device:test`
Expected: All tests pass. The `DeviceSessionProductionReadyTest` tests use `FakeIosDevice` directly so they are unaffected by the factory change.

**Step 5: Commit**

```
feat: switch iOS backend from SimctlIOSDevice to XCTestIOSDevice

LocalIOSDevice delegates UI automation (tap, hierarchy, screenshots)
to XCTestIOSDevice and app lifecycle to SimctlIOSDevice, matching
how Maestro CLI uses its iOS stack.
```

---

### Task 3: Make `connectIos` testable via function injection

The current `connectIos` is a private method that constructs real Maestro objects. To keep the factory testable (and match the Android pattern with `createWithQuery`/`discover` params), extract the iOS connection construction so tests can verify wiring without a real device.

**Files:**
- Modify: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/DeviceSessionFactory.kt`
- Modify: `verity/device/src/test/kotlin/me/chrisbanes/verity/device/DeviceSessionProductionReadyTest.kt`

**Step 1: Extract `connectIos` parameters for testability**

Change `connectIos` visibility to `internal` and add a factory parameter:

```kotlin
internal suspend fun connectIos(
  deviceId: String?,
  createSession: (String) -> DeviceSession = ::createIosSession,
): DeviceSession {
  val resolvedId = resolveIosDeviceId(deviceId)
  return withContext(Dispatchers.IO) { createSession(resolvedId) }
}

private fun createIosSession(deviceId: String): DeviceSession {
  val simctlDevice = SimctlIOSDevice(deviceId)

  val installer = LocalXCTestInstaller(
    deviceId = deviceId,
    deviceType = IOSDeviceType.SIMULATOR,
    defaultPort = 22087,
    iOSDriverConfig = LocalXCTestInstaller.IOSDriverConfig(
      prebuiltRunner = true,
      sourceDirectory = "",
      context = Context.CLI,
      snapshotKeyHonorModalViews = null,
    ),
    deviceController = simctlDevice,
  )
  val driverClient = XCTestDriverClient(installer)
  val xcTestDevice = XCTestIOSDevice(
    deviceId = deviceId,
    client = driverClient,
    getInstalledApps = { emptySet() },
  )
  val iosDevice = LocalIOSDevice(
    deviceId = deviceId,
    xcTestDevice = xcTestDevice,
    deviceController = simctlDevice,
  )

  val driver = IOSDriver(iosDevice)
  val maestro = Maestro.ios(driver, openDriver = true)
  return IosDeviceSession(maestro, iosDevice)
}
```

Update the `connect()` call site to pass through:

```kotlin
Platform.IOS -> {
  if (disableAnimations) {
    System.err.println("Warning: disableAnimations is not supported on iOS, ignoring")
  }
  connectIos(deviceId)
}
```

**Step 2: Run all device tests**

Run: `./gradlew :verity:device:test`
Expected: All tests pass

**Step 3: Commit**

```
refactor: make connectIos testable via function injection
```

---

### Task 4: Enable iOS smoke test

**Files:**
- Modify: `verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/IosSettingsSmoke.kt`

**Step 1: Remove `@Disabled` annotation**

Remove lines 19-22 (the `@Disabled(...)` annotation and its message). Keep the `@Tag("ios")` annotation.

**Step 2: Align assertion with Android test pattern**

Update the assertion to match `AndroidSettingsSmoke` (which includes a failure message):

```kotlin
val result = orchestrator.run(journey)
val failedSegment = result.segments.firstOrNull { !it.passed }
assertThat(result.passed, "segment ${failedSegment?.index} failed: ${failedSegment?.reasoning}")
  .isTrue()
```

**Step 3: Remove unused `@Disabled` import**

Remove: `import org.junit.jupiter.api.Disabled`

**Step 4: Verify compilation**

Run: `./gradlew :verity:smoke-tests:compileTestKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```
feat: enable iOS smoke test

The XCTestIOSDevice backend is fully implemented in Maestro 2.3.0,
so the SimctlIOSDevice stub workaround is no longer needed.
```

---

### Task 5: Add iOS smoke test CI job

**Files:**
- Modify: `.github/workflows/ci.yml`

**Step 1: Add `smoke-ios` job**

Add after the `smoke-android` job:

```yaml
  smoke-ios:
    runs-on: macos-latest

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - uses: gradle/actions/setup-gradle@v4

      - name: Boot iOS simulator
        run: |
          UDID=$(xcrun simctl list devices available -j | python3 -c "
          import json, sys
          data = json.load(sys.stdin)
          for runtime, devices in data['devices'].items():
              if 'iOS' in runtime or 'iPhone' in runtime:
                  for d in devices:
                      if d.get('isAvailable'):
                          print(d['udid'])
                          sys.exit(0)
          sys.exit(1)
          ")
          xcrun simctl boot "$UDID"
          echo "UDID=$UDID" >> "$GITHUB_ENV"

      - name: iOS smoke tests
        run: ./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=ios

      - name: Shutdown simulator
        if: always()
        run: xcrun simctl shutdown "$UDID" || true
```

**Step 2: Commit**

```
ci: add iOS simulator smoke test job
```

---

### Task 6: Run full check and verify

**Step 1: Run spotless**

Run: `./gradlew spotlessApply`

**Step 2: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL

**Step 3: Commit any formatting fixes**

```
style: apply spotless formatting
```
