# Shared Preflight Checks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build shared preflight checks that produce actionable CLI failures and structured MCP device/session errors before low-level runtime failures occur.

**Architecture:** Add dependency-light preflight result and path-checking types to `:verity:core`, platform command/device checkers to `:verity:device`, CLI-only provider/model/credential checks to `:verity:cli`, and MCP device/path preflight rendering to `:verity:mcp`. MCP must not validate LLM provider, model, or credential configuration.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization, kotlinx.coroutines, Clikt, MCP Kotlin SDK, assertk, Gradle via `./gradlew`.

---

## File Structure

- Create `verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/Preflight.kt`
  - Owns `PreflightReport`, `PreflightIssue`, `PreflightSeverity`, and stable issue codes.
- Create `verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/PathPreflightChecker.kt`
  - Owns readable path, writable target, and temp-directory checks.
- Create `verity/core/src/test/kotlin/me/chrisbanes/verity/core/preflight/PreflightTest.kt`
  - Tests report aggregation and rendering helpers.
- Create `verity/core/src/test/kotlin/me/chrisbanes/verity/core/preflight/PathPreflightCheckerTest.kt`
  - Tests readable and writable path behavior using temp files/directories.
- Create `verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/ProcessRunner.kt`
  - Owns injected process execution for platform checks.
- Create `verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/AndroidPreflightChecker.kt`
  - Checks ADB availability, explicit serial format, explicit serial state, and auto-discovered Android device state.
- Create `verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/IosPreflightChecker.kt`
  - Checks `xcrun simctl`, booted simulator discovery, explicit UDID state, and multiple simulator state.
- Create `verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/PlatformDevicePreflightChecker.kt`
  - Routes `Platform` to Android or iOS checker.
- Create `verity/device/src/test/kotlin/me/chrisbanes/verity/device/preflight/AndroidPreflightCheckerTest.kt`
  - Tests Android checker with fake process output.
- Create `verity/device/src/test/kotlin/me/chrisbanes/verity/device/preflight/IosPreflightCheckerTest.kt`
  - Tests iOS checker with fake `simctl` output.
- Create `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/CliPreflightChecker.kt`
  - Owns CLI-only provider, model, credential, journey path, context path, and platform preflight composition.
- Create `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt`
  - Tests provider/model/credential failures without touching real environment variables.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`
  - Runs CLI preflight before creating device sessions or LLM clients.
- Modify `verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt`
  - Runs device preflight in `open_session`, path preflight for `capture_screenshot.save_to_file`, and renders structured preflight JSON errors.
- Modify `verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt`
  - Tests `open_session` preflight failures and screenshot path failures.
- Modify `docs/architecture.md`
  - Documents module ownership and the CLI-only provider preflight scope.

---

### Task 1: Core Preflight Report Types

**Files:**
- Create: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/Preflight.kt`
- Test: `verity/core/src/test/kotlin/me/chrisbanes/verity/core/preflight/PreflightTest.kt`

- [ ] **Step 1: Write failing core report tests**

```kotlin
package me.chrisbanes.verity.core.preflight

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class PreflightTest {
  @Test
  fun `empty report passes`() {
    val report = PreflightReport()

    assertThat(report.passed).isTrue()
    assertThat(report.hasErrors).isFalse()
    assertThat(report.renderPlainText()).isEqualTo("Preflight passed.")
  }

  @Test
  fun `report with error fails and renders actionable message`() {
    val report = PreflightReport(
      issues = listOf(
        PreflightIssue(
          code = PreflightCodes.ANDROID_DEVICE_MISSING,
          severity = PreflightSeverity.ERROR,
          message = "No Android device was found.",
          remediation = "Start an emulator, connect a device, or pass --device <serial>.",
        ),
      ),
    )

    assertThat(report.passed).isFalse()
    assertThat(report.hasErrors).isTrue()
    assertThat(report.errors.size).isEqualTo(1)
    assertThat(report.renderPlainText()).contains("android.device.missing")
    assertThat(report.renderPlainText()).contains("Start an emulator")
  }

  @Test
  fun `combines reports preserving issue order`() {
    val first = PreflightReport(
      listOf(
        PreflightIssue(
          code = "first",
          severity = PreflightSeverity.WARNING,
          message = "First warning.",
          remediation = "Read the warning.",
        ),
      ),
    )
    val second = PreflightReport(
      listOf(
        PreflightIssue(
          code = "second",
          severity = PreflightSeverity.ERROR,
          message = "Second error.",
          remediation = "Fix the error.",
        ),
      ),
    )

    val combined = first + second

    assertThat(combined.issues.map { it.code }).isEqualTo(listOf("first", "second"))
    assertThat(combined.passed).isFalse()
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :verity:core:test --tests me.chrisbanes.verity.core.preflight.PreflightTest`

Expected: FAIL with unresolved references for `PreflightReport`, `PreflightIssue`, `PreflightSeverity`, and `PreflightCodes`.

- [ ] **Step 3: Add core preflight types**

```kotlin
package me.chrisbanes.verity.core.preflight

import kotlinx.serialization.Serializable

@Serializable
enum class PreflightSeverity {
  WARNING,
  ERROR,
}

@Serializable
data class PreflightIssue(
  val code: String,
  val severity: PreflightSeverity,
  val message: String,
  val remediation: String,
  val details: Map<String, String> = emptyMap(),
)

@Serializable
data class PreflightReport(
  val issues: List<PreflightIssue> = emptyList(),
) {
  val errors: List<PreflightIssue>
    get() = issues.filter { it.severity == PreflightSeverity.ERROR }

  val warnings: List<PreflightIssue>
    get() = issues.filter { it.severity == PreflightSeverity.WARNING }

  val hasErrors: Boolean
    get() = errors.isNotEmpty()

  val passed: Boolean
    get() = !hasErrors

  operator fun plus(other: PreflightReport): PreflightReport =
    PreflightReport(issues = issues + other.issues)

  fun renderPlainText(): String {
    if (issues.isEmpty()) return "Preflight passed."
    return buildString {
      appendLine("Preflight failed:")
      issues.forEach { issue ->
        appendLine("- [${issue.severity}] ${issue.code}: ${issue.message}")
        appendLine("  Remediation: ${issue.remediation}")
        if (issue.details.isNotEmpty()) {
          appendLine("  Details: ${issue.details.entries.joinToString { "${it.key}=${it.value}" }}")
        }
      }
    }.trimEnd()
  }
}

object PreflightCodes {
  const val PATH_MISSING = "path.missing"
  const val PATH_NOT_READABLE = "path.not_readable"
  const val PATH_NOT_WRITABLE = "path.not_writable"
  const val TEMP_NOT_WRITABLE = "runtime.temp_not_writable"

  const val ANDROID_DEVICE_ID_INVALID = "android.device_id.invalid"
  const val ANDROID_ADB_MISSING = "android.adb.missing"
  const val ANDROID_ADB_FAILED = "android.adb.failed"
  const val ANDROID_DEVICE_MISSING = "android.device.missing"
  const val ANDROID_DEVICE_UNAVAILABLE = "android.device.unavailable"

  const val IOS_XCRUN_MISSING = "ios.xcrun.missing"
  const val IOS_SIMCTL_FAILED = "ios.simctl.failed"
  const val IOS_SIMULATOR_NONE = "ios.simulator.none"
  const val IOS_SIMULATOR_MULTIPLE = "ios.simulator.multiple"
  const val IOS_DEVICE_NOT_BOOTED = "ios.device.not_booted"

  const val PROVIDER_UNKNOWN = "provider.unknown"
  const val PROVIDER_MODEL_UNKNOWN = "provider.model.unknown"
  const val PROVIDER_CREDENTIAL_MISSING = "provider.credential.missing"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :verity:core:test --tests me.chrisbanes.verity.core.preflight.PreflightTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/Preflight.kt verity/core/src/test/kotlin/me/chrisbanes/verity/core/preflight/PreflightTest.kt
rtk git commit -m "feat: add preflight report model"
```

---

### Task 2: Core Path Preflight Checker

**Files:**
- Create: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/PathPreflightChecker.kt`
- Test: `verity/core/src/test/kotlin/me/chrisbanes/verity/core/preflight/PathPreflightCheckerTest.kt`

- [ ] **Step 1: Write failing path checker tests**

```kotlin
package me.chrisbanes.verity.core.preflight

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class PathPreflightCheckerTest {
  @Test
  fun `readable file passes`() {
    val file = Files.createTempFile("verity-path-", ".txt")
    Files.writeString(file, "content")

    val report = PathPreflightChecker().requireReadableFile(file, "Journey file")

    assertThat(report.issues).isEmpty()
  }

  @Test
  fun `missing readable file fails`() {
    val file = Files.createTempDirectory("verity-path-").resolve("missing.yaml")

    val report = PathPreflightChecker().requireReadableFile(file, "Journey file")

    assertThat(report.passed).isFalse()
    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.PATH_MISSING)
    assertThat(report.issues.single().message).contains("Journey file")
  }

  @Test
  fun `writable target passes when parent is writable directory`() {
    val dir = Files.createTempDirectory("verity-output-")
    val target = dir.resolve("screenshot.png")

    val report = PathPreflightChecker().requireWritableFileTarget(target, "Screenshot output")

    assertThat(report.passed).isTrue()
  }

  @Test
  fun `temp directory probe passes`() = runTest {
    val report = PathPreflightChecker().requireTempWritable()

    assertThat(report.passed).isTrue()
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :verity:core:test --tests me.chrisbanes.verity.core.preflight.PathPreflightCheckerTest`

Expected: FAIL with unresolved reference `PathPreflightChecker`.

- [ ] **Step 3: Add path checker**

```kotlin
package me.chrisbanes.verity.core.preflight

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class PathPreflightChecker {
  fun requireReadableFile(path: Path, label: String): PreflightReport {
    val issues = mutableListOf<PreflightIssue>()
    if (!Files.exists(path)) {
      issues += PreflightIssue(
        code = PreflightCodes.PATH_MISSING,
        severity = PreflightSeverity.ERROR,
        message = "$label does not exist: $path",
        remediation = "Provide an existing file path.",
        details = mapOf("path" to path.toString()),
      )
    } else if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      issues += PreflightIssue(
        code = PreflightCodes.PATH_NOT_READABLE,
        severity = PreflightSeverity.ERROR,
        message = "$label is not a readable file: $path",
        remediation = "Choose a readable file path.",
        details = mapOf("path" to path.toString()),
      )
    }
    return PreflightReport(issues)
  }

  fun requireReadableDirectory(path: Path, label: String): PreflightReport {
    val issues = mutableListOf<PreflightIssue>()
    if (!Files.exists(path)) {
      issues += PreflightIssue(
        code = PreflightCodes.PATH_MISSING,
        severity = PreflightSeverity.ERROR,
        message = "$label does not exist: $path",
        remediation = "Provide an existing directory path.",
        details = mapOf("path" to path.toString()),
      )
    } else if (!Files.isDirectory(path) || !Files.isReadable(path)) {
      issues += PreflightIssue(
        code = PreflightCodes.PATH_NOT_READABLE,
        severity = PreflightSeverity.ERROR,
        message = "$label is not a readable directory: $path",
        remediation = "Choose a readable directory path.",
        details = mapOf("path" to path.toString()),
      )
    }
    return PreflightReport(issues)
  }

  fun requireWritableFileTarget(path: Path, label: String): PreflightReport {
    val parent = path.toAbsolutePath().parent
    return when {
      parent == null -> PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PATH_NOT_WRITABLE,
            severity = PreflightSeverity.ERROR,
            message = "$label has no parent directory: $path",
            remediation = "Provide a file path inside an existing writable directory.",
            details = mapOf("path" to path.toString()),
          ),
        ),
      )

      !Files.isDirectory(parent) || !Files.isWritable(parent) -> PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PATH_NOT_WRITABLE,
            severity = PreflightSeverity.ERROR,
            message = "$label parent directory is not writable: $parent",
            remediation = "Choose a path inside a writable directory.",
            details = mapOf("path" to path.toString(), "parent" to parent.toString()),
          ),
        ),
      )

      else -> PreflightReport()
    }
  }

  suspend fun requireTempWritable(): PreflightReport = withContext(Dispatchers.IO) {
    var tempFile: Path? = null
    try {
      tempFile = Files.createTempFile("verity-preflight-", ".tmp")
      PreflightReport()
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.TEMP_NOT_WRITABLE,
            severity = PreflightSeverity.ERROR,
            message = "The runtime temp directory is not writable.",
            remediation = "Set a writable temp directory before running Verity.",
            details = mapOf("error" to (e.message ?: e::class.simpleName.orEmpty())),
          ),
        ),
      )
    } finally {
      val created = tempFile
      if (created != null) {
        withContext(NonCancellable + Dispatchers.IO) {
          Files.deleteIfExists(created)
        }
      }
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `rtk ./gradlew :verity:core:test --tests me.chrisbanes.verity.core.preflight.PathPreflightCheckerTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/PathPreflightChecker.kt verity/core/src/test/kotlin/me/chrisbanes/verity/core/preflight/PathPreflightCheckerTest.kt
rtk git commit -m "feat: add path preflight checks"
```

---

### Task 3: Device Platform Preflight Checkers

**Files:**
- Create: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/ProcessRunner.kt`
- Create: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/AndroidPreflightChecker.kt`
- Create: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/IosPreflightChecker.kt`
- Create: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/PlatformDevicePreflightChecker.kt`
- Test: `verity/device/src/test/kotlin/me/chrisbanes/verity/device/preflight/AndroidPreflightCheckerTest.kt`
- Test: `verity/device/src/test/kotlin/me/chrisbanes/verity/device/preflight/IosPreflightCheckerTest.kt`

- [ ] **Step 1: Write failing Android checker tests**

```kotlin
package me.chrisbanes.verity.device.preflight

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.preflight.PreflightCodes

class AndroidPreflightCheckerTest {
  @Test
  fun `passes when adb sees a connected device`() = runTest {
    val checker = AndroidPreflightChecker(
      processRunner = FakeProcessRunner(
        mapOf(
          listOf("adb", "version") to ProcessResult(0, "Android Debug Bridge version 1.0.41"),
          listOf("adb", "devices") to ProcessResult(0, "List of devices attached\nemulator-5554\tdevice\n"),
        ),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.passed).isTrue()
  }

  @Test
  fun `reports missing adb`() = runTest {
    val checker = AndroidPreflightChecker(
      processRunner = FakeProcessRunner(
        mapOf(listOf("adb", "version") to ProcessResult(127, "adb: command not found")),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.ANDROID_ADB_MISSING)
  }

  @Test
  fun `reports no auto discovered devices`() = runTest {
    val checker = AndroidPreflightChecker(
      processRunner = FakeProcessRunner(
        mapOf(
          listOf("adb", "version") to ProcessResult(0, "ok"),
          listOf("adb", "devices") to ProcessResult(0, "List of devices attached\n\n"),
        ),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.ANDROID_DEVICE_MISSING)
  }

  @Test
  fun `rejects ip address as explicit android device id`() = runTest {
    val checker = AndroidPreflightChecker(processRunner = FakeProcessRunner(emptyMap()))

    val report = checker.check(deviceId = "192.168.1.20")

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.ANDROID_DEVICE_ID_INVALID)
  }
}

private class FakeProcessRunner(
  private val results: Map<List<String>, ProcessResult>,
) : ProcessRunner {
  override suspend fun run(command: List<String>): ProcessResult =
    results[command] ?: error("Unexpected command: $command")
}
```

- [ ] **Step 2: Write failing iOS checker tests**

```kotlin
package me.chrisbanes.verity.device.preflight

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.preflight.PreflightCodes

class IosPreflightCheckerTest {
  @Test
  fun `passes with one booted simulator`() = runTest {
    val checker = IosPreflightChecker(
      processRunner = FakeIosProcessRunner(
        ProcessResult(
          0,
          """
          {"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-18-5":[{"udid":"sim-1","state":"Booted"}]}}
          """.trimIndent(),
        ),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.passed).isTrue()
  }

  @Test
  fun `reports no booted simulator`() = runTest {
    val checker = IosPreflightChecker(
      processRunner = FakeIosProcessRunner(
        ProcessResult(
          0,
          """
          {"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-18-5":[{"udid":"sim-1","state":"Shutdown"}]}}
          """.trimIndent(),
        ),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.IOS_SIMULATOR_NONE)
  }

  @Test
  fun `reports multiple booted simulators`() = runTest {
    val checker = IosPreflightChecker(
      processRunner = FakeIosProcessRunner(
        ProcessResult(
          0,
          """
          {"devices":{"runtime":[{"udid":"sim-1","state":"Booted"},{"udid":"sim-2","state":"Booted"}]}}
          """.trimIndent(),
        ),
      ),
    )

    val report = checker.check(deviceId = null)

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.IOS_SIMULATOR_MULTIPLE)
  }

  @Test
  fun `reports explicit simulator not booted`() = runTest {
    val checker = IosPreflightChecker(
      processRunner = FakeIosProcessRunner(
        ProcessResult(
          0,
          """
          {"devices":{"runtime":[{"udid":"sim-1","state":"Shutdown"}]}}
          """.trimIndent(),
        ),
      ),
    )

    val report = checker.check(deviceId = "sim-1")

    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.IOS_DEVICE_NOT_BOOTED)
  }
}

private class FakeIosProcessRunner(
  private val result: ProcessResult,
) : ProcessRunner {
  override suspend fun run(command: List<String>): ProcessResult {
    assertThat(command).isEqualTo(listOf("xcrun", "simctl", "list", "devices", "-j"))
    return result
  }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `rtk ./gradlew :verity:device:test --tests me.chrisbanes.verity.device.preflight.AndroidPreflightCheckerTest --tests me.chrisbanes.verity.device.preflight.IosPreflightCheckerTest`

Expected: FAIL with unresolved references for the checker and process runner classes.

- [ ] **Step 4: Add process runner**

```kotlin
package me.chrisbanes.verity.device.preflight

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProcessResult(
  val exitCode: Int,
  val output: String,
)

fun interface ProcessRunner {
  suspend fun run(command: List<String>): ProcessResult
}

object LocalProcessRunner : ProcessRunner {
  override suspend fun run(command: List<String>): ProcessResult = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(command)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    ProcessResult(exitCode = process.waitFor(), output = output)
  }
}
```

- [ ] **Step 5: Add Android checker**

```kotlin
package me.chrisbanes.verity.device.preflight

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.core.preflight.PreflightIssue
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.core.preflight.PreflightSeverity

class AndroidPreflightChecker(
  private val processRunner: ProcessRunner = LocalProcessRunner,
) {
  suspend fun check(deviceId: String?): PreflightReport {
    if (deviceId != null && isIpAddress(deviceId)) {
      return PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.ANDROID_DEVICE_ID_INVALID,
            severity = PreflightSeverity.ERROR,
            message = "Invalid Android device ID '$deviceId'.",
            remediation = "Pass an ADB serial such as emulator-5554 instead of an IP address.",
            details = mapOf("device" to deviceId),
          ),
        ),
      )
    }

    val adb = runAdb(listOf("adb", "version"))
    if (adb == null || adb.exitCode == 127) {
      return PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.ANDROID_ADB_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "ADB is not available.",
            remediation = "Install Android platform-tools and ensure adb is on PATH.",
          ),
        ),
      )
    }
    if (adb.exitCode != 0) {
      return PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.ANDROID_ADB_FAILED,
            severity = PreflightSeverity.ERROR,
            message = "ADB failed to run.",
            remediation = "Fix the adb installation and verify `adb version` succeeds.",
            details = mapOf("output" to adb.output.trim()),
          ),
        ),
      )
    }

    return if (deviceId != null) {
      checkExplicitDevice(deviceId)
    } else {
      checkAutoDiscoveredDevice()
    }
  }

  private suspend fun checkExplicitDevice(deviceId: String): PreflightReport {
    val state = runAdb(listOf("adb", "-s", deviceId, "get-state"))
    return if (state?.exitCode == 0 && state.output.trim() == "device") {
      PreflightReport()
    } else {
      PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.ANDROID_DEVICE_UNAVAILABLE,
            severity = PreflightSeverity.ERROR,
            message = "Android device '$deviceId' is not available.",
            remediation = "Start or connect the device, then verify `adb -s $deviceId get-state` returns device.",
            details = mapOf("device" to deviceId, "output" to (state?.output?.trim().orEmpty())),
          ),
        ),
      )
    }
  }

  private suspend fun checkAutoDiscoveredDevice(): PreflightReport {
    val devices = runAdb(listOf("adb", "devices"))
    val hasDevice = devices?.exitCode == 0 && devices.output
      .lineSequence()
      .drop(1)
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .any { line -> line.split(Regex("\\s+")).getOrNull(1) == "device" }

    return if (hasDevice) {
      PreflightReport()
    } else {
      PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.ANDROID_DEVICE_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "No Android device was found.",
            remediation = "Start an emulator, connect a device, or pass --device <serial>.",
            details = mapOf("output" to (devices?.output?.trim().orEmpty())),
          ),
        ),
      )
    }
  }

  private suspend fun runAdb(command: List<String>): ProcessResult? =
    try {
      processRunner.run(command)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      null
    }

  private fun isIpAddress(value: String): Boolean =
    Regex("""^\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?$""").matches(value)
}
```

- [ ] **Step 6: Add iOS checker and platform router**

```kotlin
package me.chrisbanes.verity.device.preflight

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.core.preflight.PreflightIssue
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.core.preflight.PreflightSeverity

class IosPreflightChecker(
  private val processRunner: ProcessRunner = LocalProcessRunner,
) {
  suspend fun check(deviceId: String?): PreflightReport {
    val result = try {
      processRunner.run(listOf("xcrun", "simctl", "list", "devices", "-j"))
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      return PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.IOS_XCRUN_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "xcrun is not available.",
            remediation = "Install Xcode command line tools and ensure xcrun is on PATH.",
          ),
        ),
      )
    }

    if (result.exitCode != 0) {
      return PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.IOS_SIMCTL_FAILED,
            severity = PreflightSeverity.ERROR,
            message = "xcrun simctl failed.",
            remediation = "Fix the Xcode command line tools setup and verify `xcrun simctl list devices -j` succeeds.",
            details = mapOf("output" to result.output.trim()),
          ),
        ),
      )
    }

    val devices = parseDevices(result.output)
    val booted = devices.filter { it.state == "Booted" }

    return if (deviceId != null) {
      val selected = devices.firstOrNull { it.udid == deviceId }
      if (selected?.state == "Booted") {
        PreflightReport()
      } else {
        PreflightReport(
          listOf(
            PreflightIssue(
              code = PreflightCodes.IOS_DEVICE_NOT_BOOTED,
              severity = PreflightSeverity.ERROR,
              message = "iOS device '$deviceId' is not booted.",
              remediation = "Boot that simulator or pass the UDID of a booted simulator.",
              details = mapOf("device" to deviceId, "state" to (selected?.state ?: "missing")),
            ),
          ),
        )
      }
    } else {
      when (booted.size) {
        0 -> PreflightReport(
          listOf(
            PreflightIssue(
              code = PreflightCodes.IOS_SIMULATOR_NONE,
              severity = PreflightSeverity.ERROR,
              message = "No booted iOS simulator was found.",
              remediation = "Boot a simulator or pass --device <udid>.",
            ),
          ),
        )

        1 -> PreflightReport()

        else -> PreflightReport(
          listOf(
            PreflightIssue(
              code = PreflightCodes.IOS_SIMULATOR_MULTIPLE,
              severity = PreflightSeverity.ERROR,
              message = "Multiple booted iOS simulators were found.",
              remediation = "Pass --device <udid> to choose one simulator.",
              details = mapOf("devices" to booted.joinToString(", ") { it.udid }),
            ),
          ),
        )
      }
    }
  }

  private fun parseDevices(json: String): List<IosDeviceState> {
    val root = Json.parseToJsonElement(json).jsonObject
    val devices = root["devices"]?.jsonObject ?: return emptyList()
    return devices.values.flatMap { runtimeDevices ->
      runtimeDevices.jsonArray.mapNotNull { device ->
        val obj = device.jsonObject
        val udid = obj["udid"]?.jsonPrimitive?.contentOrNull
        val state = obj["state"]?.jsonPrimitive?.contentOrNull
        if (udid != null && state != null) IosDeviceState(udid, state) else null
      }
    }
  }

  private data class IosDeviceState(
    val udid: String,
    val state: String,
  )
}
```

```kotlin
package me.chrisbanes.verity.device.preflight

import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PreflightReport

fun interface DevicePreflightChecker {
  suspend fun check(platform: Platform, deviceId: String?): PreflightReport
}

class PlatformDevicePreflightChecker(
  private val androidChecker: AndroidPreflightChecker = AndroidPreflightChecker(),
  private val iosChecker: IosPreflightChecker = IosPreflightChecker(),
) : DevicePreflightChecker {
  override suspend fun check(platform: Platform, deviceId: String?): PreflightReport = when (platform) {
    Platform.ANDROID_TV,
    Platform.ANDROID_MOBILE,
    -> androidChecker.check(deviceId)

    Platform.IOS -> iosChecker.check(deviceId)
  }
}
```

- [ ] **Step 7: Run device tests**

Run: `rtk ./gradlew :verity:device:test --tests me.chrisbanes.verity.device.preflight.AndroidPreflightCheckerTest --tests me.chrisbanes.verity.device.preflight.IosPreflightCheckerTest`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
rtk git add verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight verity/device/src/test/kotlin/me/chrisbanes/verity/device/preflight
rtk git commit -m "feat: add device preflight checks"
```

---

### Task 4: CLI Provider and Path Preflight

**Files:**
- Create: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/CliPreflightChecker.kt`
- Test: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt`

- [ ] **Step 1: Write failing CLI preflight tests**

```kotlin
package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.device.preflight.DevicePreflightChecker

class CliPreflightCheckerTest {
  @Test
  fun `reports missing provider credential`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    val checker = CliPreflightChecker(
      environment = { null },
      devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
    )

    val result = checker.check(
      request = CliPreflightRequest(
        cliProvider = "anthropic",
        cliNavigatorModel = null,
        cliInspectorModel = null,
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = null,
      ),
      config = VerityConfig(),
    )

    assertThat(result.report.passed).isFalse()
    assertThat(result.report.issues.single().code).isEqualTo(PreflightCodes.PROVIDER_CREDENTIAL_MISSING)
  }

  @Test
  fun `reports unknown provider without throwing`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    val checker = CliPreflightChecker(
      devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
    )

    val result = checker.check(
      request = CliPreflightRequest(
        cliProvider = "unknown",
        cliNavigatorModel = null,
        cliInspectorModel = null,
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = null,
      ),
      config = VerityConfig(),
    )

    assertThat(result.report.issues.single().code).isEqualTo(PreflightCodes.PROVIDER_UNKNOWN)
  }

  @Test
  fun `returns resolved provider models and credential when preflight passes`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    val checker = CliPreflightChecker(
      environment = { name -> if (name == "ANTHROPIC_API_KEY") "secret" else null },
      devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
    )

    val result = checker.check(
      request = CliPreflightRequest(
        cliProvider = "anthropic",
        cliNavigatorModel = "claude-haiku-4-5",
        cliInspectorModel = "claude-opus-4-5",
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = "emulator-5554",
      ),
      config = VerityConfig(),
    )

    assertThat(result.report.passed).isTrue()
    assertThat(result.provider).isNotNull()
    assertThat(result.apiKey).isEqualTo("secret")
    assertThat(result.navigatorModel?.id).isEqualTo("claude-haiku-4-5")
    assertThat(result.inspectorModel?.id).isEqualTo("claude-opus-4-5")
  }

  @Test
  fun `runs device preflight for selected platform`() = runTest {
    val journey = Files.createTempFile("journey-", ".journey.yaml")
    var receivedPlatform: Platform? = null
    var receivedDevice: String? = null
    val checker = CliPreflightChecker(
      environment = { name -> if (name == "ANTHROPIC_API_KEY") "secret" else null },
      devicePreflightChecker = DevicePreflightChecker { platform, device ->
        receivedPlatform = platform
        receivedDevice = device
        PreflightReport()
      },
    )

    checker.check(
      request = CliPreflightRequest(
        cliProvider = "anthropic",
        cliNavigatorModel = null,
        cliInspectorModel = null,
        apiKey = null,
        journeyPath = journey.toString(),
        contextPath = null,
        platform = Platform.IOS,
        deviceId = "sim-1",
      ),
      config = VerityConfig(),
    )

    assertThat(receivedPlatform).isEqualTo(Platform.IOS)
    assertThat(receivedDevice).isEqualTo("sim-1")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.CliPreflightCheckerTest`

Expected: FAIL with unresolved references for `CliPreflightChecker`, `CliPreflightRequest`, and `CliPreflightResult`.

- [ ] **Step 3: Add CLI preflight checker**

```kotlin
package me.chrisbanes.verity.cli

import ai.koog.prompt.llm.LLModel
import java.nio.file.Path
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PathPreflightChecker
import me.chrisbanes.verity.core.preflight.PreflightCodes
import me.chrisbanes.verity.core.preflight.PreflightIssue
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.core.preflight.PreflightSeverity
import me.chrisbanes.verity.device.preflight.DevicePreflightChecker
import me.chrisbanes.verity.device.preflight.PlatformDevicePreflightChecker

data class CliPreflightRequest(
  val cliProvider: String?,
  val cliNavigatorModel: String?,
  val cliInspectorModel: String?,
  val apiKey: String?,
  val journeyPath: String?,
  val contextPath: String?,
  val platform: Platform,
  val deviceId: String?,
)

data class CliPreflightResult(
  val report: PreflightReport,
  val provider: VerityProvider?,
  val apiKey: String?,
  val navigatorModel: LLModel?,
  val inspectorModel: LLModel?,
)

class CliPreflightChecker(
  private val environment: (String) -> String? = System::getenv,
  private val pathPreflightChecker: PathPreflightChecker = PathPreflightChecker(),
  private val devicePreflightChecker: DevicePreflightChecker = PlatformDevicePreflightChecker(),
) {
  suspend fun check(
    request: CliPreflightRequest,
    config: VerityConfig,
  ): CliPreflightResult {
    var report = PreflightReport()
    val provider = runCatching { resolveProvider(request.cliProvider, config) }
      .getOrElse { error ->
        report += PreflightReport(
          listOf(
            PreflightIssue(
              code = PreflightCodes.PROVIDER_UNKNOWN,
              severity = PreflightSeverity.ERROR,
              message = error.message ?: "Unknown provider.",
              remediation = "Choose one of: ${VerityProvider.all.joinToString { it.name }}.",
              details = mapOf("provider" to (request.cliProvider ?: config.provider.orEmpty())),
            ),
          ),
        )
        null
      }

    val navigatorModel = provider?.let {
      resolveModelSafely(
        provider = it,
        cliModel = request.cliNavigatorModel,
        configModel = config.navigatorModel,
        defaultModel = it.defaultNavigatorModel,
        role = "navigator",
      ) { report += it }
    }
    val inspectorModel = provider?.let {
      resolveModelSafely(
        provider = it,
        cliModel = request.cliInspectorModel,
        configModel = config.inspectorModel,
        defaultModel = it.defaultInspectorModel,
        role = "inspector",
      ) { report += it }
    }

    val resolvedApiKey = provider?.let { selectedProvider ->
      request.apiKey ?: environment(selectedProvider.envVar)
    }
    if (provider != null && provider.requiresAuth && resolvedApiKey.isNullOrBlank()) {
      report += PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PROVIDER_CREDENTIAL_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "Provider credentials are missing for '${provider.name}'.",
            remediation = "Set ${provider.envVar} or pass --api-key.",
            details = mapOf("provider" to provider.name, "env" to provider.envVar),
          ),
        ),
      )
    }
    if (provider == VerityProvider.Bedrock && environment("AWS_SECRET_ACCESS_KEY").isNullOrBlank()) {
      report += PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PROVIDER_CREDENTIAL_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "Bedrock secret credentials are missing.",
            remediation = "Set AWS_SECRET_ACCESS_KEY for the Bedrock provider.",
            details = mapOf("provider" to provider.name, "env" to "AWS_SECRET_ACCESS_KEY"),
          ),
        ),
      )
    }

    if (request.journeyPath == null) {
      report += PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PATH_MISSING,
            severity = PreflightSeverity.ERROR,
            message = "Journey path is required.",
            remediation = "Run `verity run <path.journey.yaml>`.",
          ),
        ),
      )
    } else {
      report += pathPreflightChecker.requireReadableFile(Path.of(request.journeyPath), "Journey file")
    }

    if (request.contextPath != null) {
      report += pathPreflightChecker.requireReadableDirectory(Path.of(request.contextPath), "Context path")
    }

    report += pathPreflightChecker.requireTempWritable()
    report += devicePreflightChecker.check(request.platform, request.deviceId)

    return CliPreflightResult(
      report = report,
      provider = provider,
      apiKey = resolvedApiKey,
      navigatorModel = navigatorModel,
      inspectorModel = inspectorModel,
    )
  }

  private fun resolveModelSafely(
    provider: VerityProvider,
    cliModel: String?,
    configModel: String?,
    defaultModel: LLModel,
    role: String,
    addReport: (PreflightReport) -> Unit,
  ): LLModel? = runCatching {
    resolveModel(cliModel, configModel, defaultModel, provider)
  }.getOrElse { error ->
    addReport(
      PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PROVIDER_MODEL_UNKNOWN,
            severity = PreflightSeverity.ERROR,
            message = error.message ?: "Unknown $role model.",
            remediation = "Choose a supported ${provider.name} model ID.",
            details = mapOf("provider" to provider.name, "role" to role),
          ),
        ),
      ),
    )
    null
  }
}
```

- [ ] **Step 4: Run CLI preflight tests**

Run: `rtk ./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.CliPreflightCheckerTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/CliPreflightChecker.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt
rtk git commit -m "feat: add cli preflight checks"
```

---

### Task 5: Integrate CLI Preflight in RunCommand

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`
- Test: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt`

- [ ] **Step 1: Add rendering test for CLI failures**

Append this test to `CliPreflightCheckerTest`:

```kotlin
  @Test
  fun `plain text render contains remediation for cli error`() = runTest {
    val checker = CliPreflightChecker(
      environment = { null },
      devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
    )

    val result = checker.check(
      request = CliPreflightRequest(
        cliProvider = "anthropic",
        cliNavigatorModel = null,
        cliInspectorModel = null,
        apiKey = null,
        journeyPath = null,
        contextPath = null,
        platform = Platform.ANDROID_TV,
        deviceId = null,
      ),
      config = VerityConfig(),
    )

    val text = result.report.renderPlainText()

    assertThat(text).contains("provider.credential.missing")
    assertThat(text).contains("Set ANTHROPIC_API_KEY or pass --api-key")
    assertThat(text).contains("Run `verity run <path.journey.yaml>`")
  }
```

- [ ] **Step 2: Run test**

Run: `rtk ./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.CliPreflightCheckerTest`

Expected: PASS. This confirms the renderer is ready for `RunCommand`.

- [ ] **Step 3: Modify RunCommand to preflight before side effects**

Change `RunCommand.run()` so the setup block follows this shape:

```kotlin
    val config = VerityConfig.loadOrDefault(File("verity/config.yaml"))

    val file = journeyPath?.let { File(it) }
    val journeyForPlatform = file
      ?.takeIf { it.exists() && it.isFile && it.canRead() }
      ?.let { JourneyLoader.fromFile(it) }
    val platform = parent.platform ?: journeyForPlatform?.platform ?: Platform.ANDROID_TV

    val preflight = CliPreflightChecker().check(
      request = CliPreflightRequest(
        cliProvider = parent.provider,
        cliNavigatorModel = parent.navigatorModel,
        cliInspectorModel = parent.inspectorModel,
        apiKey = parent.apiKey,
        journeyPath = journeyPath,
        contextPath = parent.contextPath,
        platform = platform,
        deviceId = parent.device,
      ),
      config = config,
    )
    if (!preflight.report.passed) {
      throw CliktError(preflight.report.renderPlainText())
    }

    val provider = checkNotNull(preflight.provider)
    val apiKey = preflight.apiKey.orEmpty()
    val navigatorModel = checkNotNull(preflight.navigatorModel)
    val inspectorModel = checkNotNull(preflight.inspectorModel)
    val journeyFile = checkNotNull(file)
    val journey = journeyForPlatform ?: JourneyLoader.fromFile(journeyFile)
```

Keep the existing echo, session creation, executor creation, and orchestrator flow after this block. Use the `platform` value from this block when connecting to `DeviceSessionFactory`.

- [ ] **Step 4: Run CLI tests**

Run: `rtk ./gradlew :verity:cli:test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt
rtk git commit -m "feat: run preflight before cli execution"
```

---

### Task 6: Integrate MCP Device and Output Path Preflight

**Files:**
- Modify: `verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt`
- Test: `verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt`

- [ ] **Step 1: Add failing MCP preflight tests**

Append these tests to `VerityMcpServerTest`:

Add these imports if they are not already present:

```kotlin
import assertk.assertions.isFalse
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
```

```kotlin
  @Test
  fun `open_session returns structured preflight error and does not open session`() = runTest {
    var sessionFactoryCalled = false
    val server = VerityMcpServer(
      sessionManager = McpDeviceSessionManager { _, _, _ ->
        sessionFactoryCalled = true
        error("session factory should not be called")
      },
      devicePreflightChecker = me.chrisbanes.verity.device.preflight.DevicePreflightChecker { _, _ ->
        me.chrisbanes.verity.core.preflight.PreflightReport(
          listOf(
            me.chrisbanes.verity.core.preflight.PreflightIssue(
              code = me.chrisbanes.verity.core.preflight.PreflightCodes.ANDROID_DEVICE_MISSING,
              severity = me.chrisbanes.verity.core.preflight.PreflightSeverity.ERROR,
              message = "No Android device was found.",
              remediation = "Start an emulator.",
            ),
          ),
        )
      },
    ).create()

    val request = CallToolRequest(
      CallToolRequestParams(
        name = "open_session",
        arguments = buildJsonObject {
          put("platform", JsonPrimitive("android"))
        },
      ),
    )

    val result = server.tools["open_session"]!!.handler.invoke(StubClientConnection(), request)
    val text = (result.content.first() as TextContent).text

    assertThat(result.isError).isEqualTo(true)
    assertThat(text).contains("android.device.missing")
    assertThat(text).contains("No Android device was found")
    assertThat(sessionFactoryCalled).isFalse()
  }

  @Test
  fun `capture_screenshot returns preflight error for unwritable save target`() = runTest {
    val session = me.chrisbanes.verity.device.FakeDeviceSession()
    val manager = McpDeviceSessionManager { _, _, _ -> session }
    val handle = manager.open(me.chrisbanes.verity.core.model.Platform.ANDROID_MOBILE, "device")
    val server = VerityMcpServer(
      sessionManager = manager,
      devicePreflightChecker = me.chrisbanes.verity.device.preflight.DevicePreflightChecker { _, _ ->
        me.chrisbanes.verity.core.preflight.PreflightReport()
      },
    ).create()

    val request = CallToolRequest(
      CallToolRequestParams(
        name = "capture_screenshot",
        arguments = buildJsonObject {
          put("session_id", JsonPrimitive(handle.sessionId.toString()))
          put("save_to_file", JsonPrimitive("/missing-parent/screenshot.png"))
        },
      ),
    )

    val result = server.tools["capture_screenshot"]!!.handler.invoke(StubClientConnection(), request)
    val text = (result.content.first() as TextContent).text

    assertThat(result.isError).isEqualTo(true)
    assertThat(text).contains("path.not_writable")
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `rtk ./gradlew :verity:mcp:test --tests me.chrisbanes.verity.mcp.VerityMcpServerTest`

Expected: FAIL because `VerityMcpServer` does not accept `devicePreflightChecker` and does not render preflight JSON.

- [ ] **Step 3: Modify VerityMcpServer constructor and rendering helpers**

Add imports:

```kotlin
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.chrisbanes.verity.core.preflight.PathPreflightChecker
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.device.preflight.DevicePreflightChecker
import me.chrisbanes.verity.device.preflight.PlatformDevicePreflightChecker
```

Change the constructor:

```kotlin
class VerityMcpServer(
  private val sessionManager: McpDeviceSessionManager = McpDeviceSessionManager(),
  private val snapshotStore: McpHierarchySnapshotStore = McpHierarchySnapshotStore(),
  private val contextPath: File? = null,
  private val skipBundledContext: Boolean = false,
  private val devicePreflightChecker: DevicePreflightChecker = PlatformDevicePreflightChecker(),
  private val pathPreflightChecker: PathPreflightChecker = PathPreflightChecker(),
) {
```

Add helpers near `success` and `error`:

```kotlin
  private val preflightJson = Json {
    prettyPrint = true
  }

  private fun preflightError(report: PreflightReport) = CallToolResult(
    content = listOf(TextContent(text = preflightJson.encodeToString(report))),
    isError = true,
  )
```

- [ ] **Step 4: Add open_session preflight**

Inside `registerOpenSession`, before `sessionManager.open(...)`, insert:

```kotlin
      val report = devicePreflightChecker.check(platform, device)
      if (!report.passed) return@addSafeTool preflightError(report)
```

- [ ] **Step 5: Add capture_screenshot save path preflight**

Inside the `if (saveToFile != null)` branch, before `session.captureScreenshot(target)`, insert:

```kotlin
          val target = Path.of(saveToFile)
          val report = pathPreflightChecker.requireWritableFileTarget(target, "Screenshot output")
          if (!report.passed) return@withSession preflightError(report)
          session.captureScreenshot(target)
          success("Screenshot saved to: $saveToFile")
```

Remove the previous duplicate `val target = Path.of(saveToFile)` line in that branch.

- [ ] **Step 6: Run MCP tests**

Run: `rtk ./gradlew :verity:mcp:test --tests me.chrisbanes.verity.mcp.VerityMcpServerTest`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
rtk git add verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt
rtk git commit -m "feat: return mcp preflight errors"
```

---

### Task 7: Architecture Documentation

**Files:**
- Modify: `docs/architecture.md`

- [ ] **Step 1: Update module dependency graph text**

In `docs/architecture.md`, add a short preflight subsection after the Device Layer factory section:

```markdown
### Preflight Checks

Verity validates the local environment before opening device sessions or constructing LLM clients.

| Module | Responsibility |
|--------|----------------|
| `:verity:core` | `PreflightReport`, `PreflightIssue`, issue codes, and path/temp filesystem checks |
| `:verity:device` | Android ADB checks, iOS `xcrun simctl` checks, and platform routing |
| `:verity:cli` | CLI-only provider, model, credential, journey path, context path, and selected platform composition |
| `:verity:mcp` | Device/session preflight for `open_session` and output path checks for screenshot files |

MCP intentionally does not validate LLM provider, model, or credential configuration. The MCP server exposes raw device tools; LLM execution belongs to the external MCP caller.
```

- [ ] **Step 2: Run documentation diff check**

Run: `rtk git diff --check docs/architecture.md`

Expected: no output and exit code 0.

- [ ] **Step 3: Commit**

```bash
rtk git add docs/architecture.md
rtk git commit -m "docs: document preflight architecture"
```

---

### Task 8: Full Verification and Cleanup

**Files:**
- Modify only files touched by previous tasks if verification finds formatting or compile issues.

- [ ] **Step 1: Run Spotless apply**

Run: `rtk ./gradlew spotlessApply`

Expected: SUCCESS.

- [ ] **Step 2: Run focused tests**

Run:

```bash
rtk ./gradlew :verity:core:test :verity:device:test :verity:cli:test :verity:mcp:test
```

Expected: SUCCESS.

- [ ] **Step 3: Run full check**

Run: `rtk ./gradlew check`

Expected: SUCCESS.

- [ ] **Step 4: Inspect final git diff**

Run: `rtk git status --short` and `rtk git log --oneline -8`

Expected: clean worktree after commits, with task commits visible at the top of the branch.

- [ ] **Step 5: Final commit if Spotless changed files after prior commits**

If `spotlessApply` changed files after Task 7 commits, commit only those formatting changes:

```bash
rtk git add verity docs
rtk git commit -m "style: apply formatting"
```

Skip this step when `rtk git status --short` is clean.
