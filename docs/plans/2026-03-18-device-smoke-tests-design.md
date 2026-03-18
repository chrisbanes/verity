# Device Smoke Tests

## Goal

Validate that Verity's device layer works against real Android emulators and iOS simulators, without requiring an LLM.

## Decisions

- **Test level**: Orchestrator-level. Tests load journey YAML and run it through the real `Orchestrator`, exercising device connection, key presses, Maestro flow execution, hierarchy capture, and text assertions.
- **Target app**: Settings (pre-installed on both platforms, predictable UI).
- **Module**: Dedicated `:verity:smoke-tests` Gradle module, excluded from `./gradlew check`.
- **Device lifecycle**: Auto-boot an emulator or simulator if none is running. Shut down only what the test started.
- **LLM avoidance**: Android TV journeys use key-mapped fast-path actions (no agent called). iOS journeys use a `FakeTextAgent`-backed navigator that returns hardcoded Maestro YAML.

## Module Structure

```
verity/smoke-tests/
├── build.gradle.kts
└── src/test/
    ├── kotlin/me/chrisbanes/verity/smoke/
    │   ├── AndroidSettingsSmoke.kt
    │   ├── IosSettingsSmoke.kt
    │   └── DeviceLifecycle.kt
    └── resources/
        ├── android-settings.journey.yaml
        └── ios-settings.journey.yaml
```

## DeviceLifecycle

A helper object responsible for booting and tearing down devices.

**Android:**

1. Check `Dadb.discover()` for a running emulator.
2. If none found, launch one via `emulator -avd <name> -no-window -no-audio`.
3. Wait for `adb wait-for-device && adb shell getprop sys.boot_completed`.
4. Track whether the test started the emulator so `close()` only kills what it owns.

**iOS:**

1. Check `xcrun simctl list devices booted -j` for a running simulator.
2. If none found, pick the first available iPhone runtime and boot it.
3. Wait for the booted state to stabilize.
4. On teardown, shut down only if the test booted it.

Gradle properties control device selection:

- `verity.smoke.avd` — Android AVD name (default: first available).
- `verity.smoke.ios.udid` — iOS simulator UDID (default: first available iPhone).

## Journey Files

**`android-settings.journey.yaml`:**

```yaml
name: Android Settings smoke
app: com.android.settings
platform: android-tv
steps:
  - Press d-pad down
  - "[?] Network"
```

All actions are key-mappable, so the Orchestrator takes the fast path. The assertion uses `VISIBLE` mode (deterministic text search). No agent is invoked.

**`ios-settings.journey.yaml`:**

```yaml
name: iOS Settings smoke
app: com.apple.Preferences
platform: ios
steps:
  - Tap General
  - "[?] About"
```

"Tap General" is not key-mappable, so the Orchestrator calls the navigator. The test injects a `FakeTextAgent` that returns a hardcoded Maestro flow:

```yaml
appId: com.apple.Preferences
---
- tapOn: General
```

## Test Structure

```kotlin
@Tag("android")
class AndroidSettingsSmoke {
  companion object {
    private lateinit var session: DeviceSession
    private lateinit var lifecycle: DeviceLifecycle

    @BeforeAll @JvmStatic
    fun boot() {
      lifecycle = DeviceLifecycle.android()
      session = lifecycle.connect()
    }

    @AfterAll @JvmStatic
    fun shutdown() {
      session.close()
      lifecycle.teardown()
    }
  }

  @Test
  fun `settings journey passes`() = runTest {
    val journey = JourneyLoader.fromResource("/android-settings.journey.yaml")
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { error("fast path: navigator should not be called") },
      inspectorFactory = { error("VISIBLE mode: inspector should not be called") },
    )
    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
  }
}

@Tag("ios")
class IosSettingsSmoke {
  companion object {
    private lateinit var session: DeviceSession
    private lateinit var lifecycle: DeviceLifecycle

    @BeforeAll @JvmStatic
    fun boot() {
      lifecycle = DeviceLifecycle.ios()
      session = lifecycle.connect()
    }

    @AfterAll @JvmStatic
    fun shutdown() {
      session.close()
      lifecycle.teardown()
    }
  }

  @Test
  fun `settings journey passes`() = runTest {
    val journey = JourneyLoader.fromResource("/ios-settings.journey.yaml")
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent(FakeTextAgent { _ ->
          "appId: com.apple.Preferences\n---\n- tapOn: General"
        })
      },
      inspectorFactory = { error("VISIBLE mode: inspector should not be called") },
    )
    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
  }
}
```

## Gradle Integration

- `./gradlew check` does **not** run smoke tests.
- Run Android tests: `./gradlew :verity:smoke-tests:test -Dinclude.tags=android`
- Run iOS tests: `./gradlew :verity:smoke-tests:test -Dinclude.tags=ios`
- Run both: `./gradlew :verity:smoke-tests:test`

The module depends on `:verity:device`, `:verity:agent` (for `Orchestrator` and `FakeTextAgent`), and `:verity:core` (for `JourneyLoader`).

## Out of Scope

- CI integration (macOS runners with emulator images) — follow-up.
- Adding taps/swipes to the fast path — separate change to core Orchestrator.
- Full end-to-end tests with a real LLM.
