# Device Smoke Tests Implementation Plan

**Goal:** Add a `:verity:smoke-tests` module that validates device connectivity and basic journey execution against real Android emulators and iOS simulators.

**Architecture:** A dedicated Gradle module excluded from `./gradlew check`. Tests use real `DeviceSession` connections via `DeviceSessionFactory.connect()`, real `Orchestrator` with fake agents, and the Settings app as the test target. A `DeviceLifecycle` helper auto-boots emulators/simulators when none are running.

**Tech Stack:** JUnit 5 with tags, Gradle test filtering, `DeviceSessionFactory`, `Orchestrator`, `FakeTextAgent`, `xcrun simctl`, Android emulator CLI.

---

### Task 1: Add smoke-tests module to settings.gradle.kts

**Files:**
- Modify: `settings.gradle.kts:13` (add include)

**Step 1: Add the module include**

Add after the last `include` line in `settings.gradle.kts`:

```kotlin
include(":verity:smoke-tests")
```

**Step 2: Verify Gradle sync**

Run: `./gradlew projects`
Expected: `:verity:smoke-tests` appears in the project list.

**Step 3: Commit**

```bash
git add settings.gradle.kts
git commit -m "build: add smoke-tests module to settings"
```

---

### Task 2: Create smoke-tests build.gradle.kts

**Files:**
- Create: `verity/smoke-tests/build.gradle.kts`

**Step 1: Create the build file**

```kotlin
dependencies {
  testImplementation(project(":verity:core"))
  testImplementation(project(":verity:device"))
  testImplementation(project(":verity:agent"))
  testImplementation(testFixtures(project(":verity:agent")))
}

tasks.test {
  // Excluded from ./gradlew check by default.
  // Run explicitly: ./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=android
  //            or:  ./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=ios
  enabled = false
}

tasks.register<Test>("smokeTest") {
  description = "Run device smoke tests (requires a running emulator or simulator)"
  group = "verification"

  useJUnitPlatform {
    val tags = System.getProperty("include.tags")
    if (tags != null) {
      includeTags(tags)
    }
  }

  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
}
```

**Step 2: Verify Gradle sync**

Run: `./gradlew :verity:smoke-tests:tasks --group verification`
Expected: `smokeTest` task appears.

**Step 3: Commit**

```bash
git add verity/smoke-tests/build.gradle.kts
git commit -m "build: create smoke-tests module with smokeTest task"
```

---

### Task 3: Create DeviceLifecycle helper

**Files:**
- Create: `verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/DeviceLifecycle.kt`

This helper discovers or boots a device, then tears down only what it started.

**Step 1: Write the failing test**

Create `verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/DeviceLifecycleTest.kt`:

```kotlin
package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isNotNull
import kotlin.test.Test

class DeviceLifecycleTest {
  @Test
  fun `android lifecycle creates without error`() {
    val lifecycle = DeviceLifecycle.android()
    assertThat(lifecycle).isNotNull()
  }

  @Test
  fun `ios lifecycle creates without error`() {
    val lifecycle = DeviceLifecycle.ios()
    assertThat(lifecycle).isNotNull()
  }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :verity:smoke-tests:smokeTest --tests "*.DeviceLifecycleTest"`
Expected: Compilation error — `DeviceLifecycle` does not exist.

**Step 3: Write DeviceLifecycle implementation**

Create `verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/DeviceLifecycle.kt`:

```kotlin
package me.chrisbanes.verity.smoke

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.DeviceSessionFactory

/**
 * Manages device lifecycle for smoke tests.
 * Discovers a running device or boots one, and tears down only what it started.
 */
class DeviceLifecycle private constructor(
  val platform: Platform,
  private val bootedByUs: Boolean,
  private val processToKill: Process?,
  private val simulatorUdid: String?,
) : AutoCloseable {

  suspend fun connect(): DeviceSession {
    return DeviceSessionFactory.connect(
      platform = platform,
      deviceId = simulatorUdid,
      disableAnimations = platform != Platform.IOS,
    )
  }

  override fun close() {
    if (!bootedByUs) return

    when (platform) {
      Platform.ANDROID_TV, Platform.ANDROID_MOBILE -> {
        processToKill?.destroyForcibly()
      }
      Platform.IOS -> {
        simulatorUdid?.let { udid ->
          ProcessBuilder("xcrun", "simctl", "shutdown", udid)
            .redirectErrorStream(true)
            .start()
            .waitFor()
        }
      }
    }
  }

  companion object {
    fun android(): DeviceLifecycle = DeviceLifecycle(
      platform = Platform.ANDROID_TV,
      bootedByUs = false,
      processToKill = null,
      simulatorUdid = null,
    )

    fun ios(): DeviceLifecycle = DeviceLifecycle(
      platform = Platform.IOS,
      bootedByUs = false,
      processToKill = null,
      simulatorUdid = null,
    )

    /**
     * Discover a running Android emulator, or boot one.
     * Uses the AVD name from the `verity.smoke.avd` system property,
     * or picks the first available AVD.
     */
    suspend fun discoverOrBootAndroid(): DeviceLifecycle = withContext(Dispatchers.IO) {
      // Check for already-running emulator
      val existingDevice = try {
        dadb.Dadb.discover()
      } catch (_: Exception) {
        null
      }

      if (existingDevice != null) {
        existingDevice.close()
        return@withContext DeviceLifecycle(
          platform = Platform.ANDROID_TV,
          bootedByUs = false,
          processToKill = null,
          simulatorUdid = null,
        )
      }

      // Boot an emulator
      val avdName = System.getProperty("verity.smoke.avd") ?: findFirstAvd()
      val process = ProcessBuilder(
        "emulator", "-avd", avdName, "-no-window", "-no-audio", "-no-boot-anim",
      ).redirectErrorStream(true).start()

      // Wait for boot
      withTimeout(3.minutes) {
        waitForAndroidBoot()
      }

      DeviceLifecycle(
        platform = Platform.ANDROID_TV,
        bootedByUs = true,
        processToKill = process,
        simulatorUdid = null,
      )
    }

    /**
     * Discover a running iOS simulator, or boot one.
     * Uses the UDID from the `verity.smoke.ios.udid` system property,
     * or picks the first available iPhone simulator.
     */
    suspend fun discoverOrBootIos(): DeviceLifecycle = withContext(Dispatchers.IO) {
      val bootedUdids = discoverBootedIosSimulators()

      if (bootedUdids.isNotEmpty()) {
        return@withContext DeviceLifecycle(
          platform = Platform.IOS,
          bootedByUs = false,
          processToKill = null,
          simulatorUdid = bootedUdids.first(),
        )
      }

      // Boot a simulator
      val udid = System.getProperty("verity.smoke.ios.udid") ?: findFirstIosSimulator()
      ProcessBuilder("xcrun", "simctl", "boot", udid)
        .redirectErrorStream(true)
        .start()
        .waitFor()

      // Wait for boot to stabilize
      withTimeout(2.minutes) {
        waitForIosBoot(udid)
      }

      DeviceLifecycle(
        platform = Platform.IOS,
        bootedByUs = true,
        processToKill = null,
        simulatorUdid = udid,
      )
    }

    private fun findFirstAvd(): String {
      val process = ProcessBuilder("emulator", "-list-avds")
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      check(process.waitFor() == 0) { "emulator -list-avds failed: $output" }
      val avds = output.lines().filter { it.isNotBlank() }
      check(avds.isNotEmpty()) { "No AVDs found. Create one with avdmanager." }
      return avds.first()
    }

    private suspend fun waitForAndroidBoot() {
      while (true) {
        val result = runCatching {
          val p = ProcessBuilder("adb", "shell", "getprop", "sys.boot_completed")
            .redirectErrorStream(true)
            .start()
          p.inputStream.bufferedReader().readText().trim()
        }
        if (result.getOrNull() == "1") break
        delay(2.seconds)
      }
    }

    private fun discoverBootedIosSimulators(): List<String> {
      val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "booted", "-j")
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      if (process.waitFor() != 0) return emptyList()

      val root = Json.parseToJsonElement(output).jsonObject
      val devices = root["devices"]?.jsonObject ?: return emptyList()
      return devices.values.flatMap { runtimeDevices ->
        runtimeDevices.jsonArray.mapNotNull { device ->
          device.jsonObject["udid"]?.jsonPrimitive?.contentOrNull
        }
      }.distinct()
    }

    private fun findFirstIosSimulator(): String {
      val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "available", "-j")
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      check(process.waitFor() == 0) { "xcrun simctl list failed: $output" }

      val root = Json.parseToJsonElement(output).jsonObject
      val devices = root["devices"]?.jsonObject ?: error("No simulators found")
      for ((runtime, runtimeDevices) in devices) {
        if (!runtime.contains("iPhone") && !runtime.contains("iOS")) continue
        for (device in runtimeDevices.jsonArray) {
          val udid = device.jsonObject["udid"]?.jsonPrimitive?.contentOrNull
          if (udid != null) return udid
        }
      }
      error("No iPhone simulator found. Create one with xcrun simctl.")
    }

    private suspend fun waitForIosBoot(udid: String) {
      while (true) {
        val booted = discoverBootedIosSimulators()
        if (udid in booted) break
        delay(2.seconds)
      }
    }
  }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :verity:smoke-tests:smokeTest --tests "*.DeviceLifecycleTest"`
Expected: PASS (these tests only instantiate the object, no device needed).

**Step 5: Commit**

```bash
git add verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/
git commit -m "feat: add DeviceLifecycle helper for smoke tests"
```

---

### Task 4: Create Android Settings journey file

**Files:**
- Create: `verity/smoke-tests/src/test/resources/android-settings.journey.yaml`

**Step 1: Create the journey file**

```yaml
name: Android Settings smoke
app: com.android.settings
platform: android-tv
steps:
  - Press d-pad down
  - "[?] Network"
```

**Step 2: Write a test that loads it**

Create `verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/JourneyLoadTest.kt`:

```kotlin
package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.model.Platform

class JourneyLoadTest {
  @Test
  fun `android settings journey loads`() {
    val url = javaClass.classLoader.getResource("android-settings.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))
    assertThat(journey.name).isEqualTo("Android Settings smoke")
    assertThat(journey.platform).isEqualTo(Platform.ANDROID_TV)
  }

  @Test
  fun `ios settings journey loads`() {
    val url = javaClass.classLoader.getResource("ios-settings.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))
    assertThat(journey.name).isEqualTo("iOS Settings smoke")
    assertThat(journey.platform).isEqualTo(Platform.IOS)
  }
}
```

**Step 3: Run test to verify it fails**

Run: `./gradlew :verity:smoke-tests:smokeTest --tests "*.JourneyLoadTest.android*"`
Expected: FAIL — resource not found or journey parsing issue. (Validates we haven't accidentally left it passing.)

Wait — it should actually pass if the YAML is correct. Run it:

Run: `./gradlew :verity:smoke-tests:smokeTest --tests "*.JourneyLoadTest.android*"`
Expected: PASS.

**Step 4: Commit**

```bash
git add verity/smoke-tests/src/test/resources/android-settings.journey.yaml
git add verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/JourneyLoadTest.kt
git commit -m "feat: add Android Settings journey and load test"
```

---

### Task 5: Create iOS Settings journey file

**Files:**
- Create: `verity/smoke-tests/src/test/resources/ios-settings.journey.yaml`

**Step 1: Create the journey file**

```yaml
name: iOS Settings smoke
app: com.apple.Preferences
platform: ios
steps:
  - Tap General
  - "[?] About"
```

**Step 2: Run the load test**

Run: `./gradlew :verity:smoke-tests:smokeTest --tests "*.JourneyLoadTest.ios*"`
Expected: PASS.

**Step 3: Commit**

```bash
git add verity/smoke-tests/src/test/resources/ios-settings.journey.yaml
git commit -m "feat: add iOS Settings journey file"
```

---

### Task 6: Create AndroidSettingsSmoke test

**Files:**
- Create: `verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/AndroidSettingsSmoke.kt`

**Step 1: Write the test**

```kotlin
package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import me.chrisbanes.verity.agent.FakeTextAgent
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.Orchestrator
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.device.DeviceSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag

@Tag("android")
class AndroidSettingsSmoke {
  companion object {
    private lateinit var lifecycle: DeviceLifecycle
    private lateinit var session: DeviceSession

    @BeforeAll
    @JvmStatic
    fun boot() {
      lifecycle = runBlocking { DeviceLifecycle.discoverOrBootAndroid() }
      session = runBlocking { lifecycle.connect() }
    }

    @AfterAll
    @JvmStatic
    fun shutdown() {
      session.close()
      lifecycle.close()
    }
  }

  @Test
  fun `settings journey passes`() = runBlocking {
    val url = javaClass.classLoader.getResource("android-settings.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))

    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _ ->
          FakeTextAgent { error("fast path: navigator should not be called") }
        }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("VISIBLE mode: inspector should not be called") } },
          evaluateVisualContent = { _, _, _ -> error("inspector visual should not be called") },
        )
      },
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
  }
}
```

Note: uses `runBlocking` rather than `runTest` because `runTest` uses virtual time, which conflicts with real device I/O timeouts.

**Step 2: Verify it compiles**

Run: `./gradlew :verity:smoke-tests:compileTestKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/AndroidSettingsSmoke.kt
git commit -m "feat: add Android Settings smoke test"
```

**Step 4: Run against a real emulator (manual verification)**

Run: `./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=android`
Expected: PASS (requires a running Android TV emulator with Settings app).

---

### Task 7: Create IosSettingsSmoke test

**Files:**
- Create: `verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/IosSettingsSmoke.kt`

**Step 1: Write the test**

```kotlin
package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import me.chrisbanes.verity.agent.FakeTextAgent
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.Orchestrator
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.device.DeviceSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag

@Tag("ios")
class IosSettingsSmoke {
  companion object {
    private lateinit var lifecycle: DeviceLifecycle
    private lateinit var session: DeviceSession

    @BeforeAll
    @JvmStatic
    fun boot() {
      lifecycle = runBlocking { DeviceLifecycle.discoverOrBootIos() }
      session = runBlocking { lifecycle.connect() }
    }

    @AfterAll
    @JvmStatic
    fun shutdown() {
      session.close()
      lifecycle.close()
    }
  }

  @Test
  fun `settings journey passes`() = runBlocking {
    val url = javaClass.classLoader.getResource("ios-settings.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))

    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _ ->
          FakeTextAgent { _ ->
            "appId: com.apple.Preferences\n---\n- tapOn: General"
          }
        }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("VISIBLE mode: inspector should not be called") } },
          evaluateVisualContent = { _, _, _ -> error("inspector visual should not be called") },
        )
      },
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
  }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :verity:smoke-tests:compileTestKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/IosSettingsSmoke.kt
git commit -m "feat: add iOS Settings smoke test"
```

**Step 4: Run against a real simulator (manual verification)**

Run: `./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=ios`
Expected: PASS (requires macOS with iOS simulator runtime).

---

### Task 8: Verify ./gradlew check still works

**Step 1: Run the full check suite**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. Smoke tests are NOT executed (the `test` task in smoke-tests is disabled).

**Step 2: Verify spotless**

Run: `./gradlew spotlessCheck`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit if any spotless fixes needed**

Run: `./gradlew spotlessApply`

```bash
git add -A
git commit -m "style: apply spotless formatting"
```

---

### Task 9: Final commit and summary

**Step 1: Verify all unit tests pass**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL.

**Step 2: Review the final file tree**

```
verity/smoke-tests/
├── build.gradle.kts
└── src/test/
    ├── kotlin/me/chrisbanes/verity/smoke/
    │   ├── AndroidSettingsSmoke.kt
    │   ├── DeviceLifecycle.kt
    │   ├── DeviceLifecycleTest.kt
    │   ├── IosSettingsSmoke.kt
    │   └── JourneyLoadTest.kt
    └── resources/
        ├── android-settings.journey.yaml
        └── ios-settings.journey.yaml
```

**Usage:**
- `./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=android` — Android only
- `./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=ios` — iOS only
- `./gradlew :verity:smoke-tests:smokeTest` — both platforms
- `./gradlew check` — unaffected, smoke tests excluded
