# Device Smoke Tests

## Goal

Validate that Verity's device layer works against real Android emulators and iOS simulators, without requiring an LLM.

## Decisions

- **Test level**: Orchestrator-level. Tests load journey YAML and run it through the real `Orchestrator`, exercising device connection, Maestro flow execution, hierarchy capture, and text assertions.
- **Target app**: Settings (pre-installed on both platforms, predictable UI).
- **Module**: Dedicated `:verity:smoke-tests` Gradle module, excluded from `./gradlew check`.
- **Device lifecycle**: Auto-boot an emulator or simulator if none is running. Shut down only what the test started.
- **LLM avoidance**: All journeys use a `FakeTextAgent`-backed navigator that returns hardcoded Maestro YAML.

## Module Structure

```
verity/smoke-tests/
├── build.gradle.kts
└── src/test/
    ├── kotlin/me/chrisbanes/verity/smoke/
    │   ├── AndroidSettingsSmoke.kt
    │   ├── IosSettingsSmoke.kt
    │   ├── DeviceLifecycle.kt
    │   ├── DeviceLifecycleTest.kt
    │   └── JourneyLoadTest.kt
    └── resources/
        ├── android-settings.journey.yaml
        └── ios-settings.journey.yaml
```

## DeviceLifecycle

A helper object responsible for booting and tearing down devices.

**Android:**

1. Check `Dadb.discover()` for a running emulator.
2. If none found, launch one via `emulator -avd <name> -no-window -no-audio`.
3. Wait for `adb shell getprop sys.boot_completed` to return `1`.
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
platform: android
steps:
  - Tap Network & internet
  - "[?] Internet"
```

"Tap Network & internet" is not key-mappable on Android mobile, so the Orchestrator calls the navigator. The test injects a `FakeTextAgent` that returns hardcoded Maestro YAML including `launchApp` and `tapOn`. The assertion uses `VISIBLE` mode (deterministic text search).

**`ios-settings.journey.yaml`:**

```yaml
name: iOS Settings smoke
app: com.apple.Preferences
platform: ios
steps:
  - Tap General
  - "[?] About"
```

Same pattern as Android — `FakeTextAgent` returns hardcoded Maestro flow with `launchApp` and `tapOn`.

## Gradle Integration

- `./gradlew check` does **not** run smoke tests.
- Run Android tests: `./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=android`
- Run iOS tests: `./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=ios`
- Run both: `./gradlew :verity:smoke-tests:smokeTest`

The module depends on `:verity:device`, `:verity:agent` (for `Orchestrator` and `FakeTextAgent`), and `:verity:core` (for `JourneyLoader`).

## Known Limitations

- **iOS disabled**: `SimctlIOSDevice` in Maestro 2.3.0 is entirely unimplemented (all methods are `TODO()` stubs). iOS smoke tests are `@Disabled` until Maestro ships a working implementation.
- **gRPC version pinning**: The smoke-tests module forces gRPC 1.50.2 to maintain compatibility with Maestro 2.3.0, which requires `AbstractManagedChannelImplBuilder` (removed in gRPC 1.57).

## Out of Scope

- Adding taps/swipes to the fast path — separate change to core Orchestrator.
- Full end-to-end tests with a real LLM.
