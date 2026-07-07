# Directory Suite Runs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `verity run <directory>` execute all matching journey files in deterministic filename order while preserving single-file behavior.

**Architecture:** Keep this change in `:verity:cli`. Add small CLI-owned suite resolution/result types, make `RunCommand` resolve an input path to an ordered list of journeys, and run that list through one suite loop. Tests inject a fake suite runner so CLI behavior can be verified without a device or LLM.

**Tech Stack:** Kotlin/JVM, Clikt, assertk, kotlinx.coroutines, existing Gradle wrapper tasks.

---

## File Structure

- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`
  - Add `ResolvedJourney`, `ResolvedJourneyResult`, and `SuiteRunResult`.
  - Add path resolution helpers.
  - Add an optional injected suite runner for CLI tests.
  - Split production execution into a private `runSuiteWithDevice(...)` function.
  - Print per-journey and aggregate console output.
- Add `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt`
  - CLI-level tests for single-file input, sorted directory input, empty directory error, failure context, and aggregate failure outcome.
- Modify `docs/architecture.md`
  - Update the CLI command description and CLI run data flow to mention file-or-directory input and suite aggregation.

---

### Task 1: Add CLI-Level Tests for Suite Input Resolution and Reporting

**Files:**
- Create: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt`
- Modify: none

- [ ] **Step 1: Write the failing test file**

Create `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt` with:

```kotlin
package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.Test
import me.chrisbanes.verity.agent.JourneyResult
import me.chrisbanes.verity.agent.SegmentResult

class RunCommandTest {

  @Test
  fun `single-file input runs one journey`() {
    val dir = createTempDir(prefix = "verity-run-single")
    try {
      val file = writeJourney(dir, "single.journey.yaml", "Single journey")
      val seen = mutableListOf<String>()
      val command = runCommand { journeys ->
        seen += journeys.map { it.file.name }
        SuiteRunResult(
          journeys.map { resolved ->
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(resolved.journey.name, listOf(SegmentResult(index = 0, passed = true))),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("run ${file.absolutePath}")

      assertThat(result.statusCode).isEqualTo(0)
      assertThat(seen).containsExactly("single.journey.yaml")
      assertThat(result.output).contains("Journey: Single journey")
      assertThat(result.output).contains("File: ${file.absolutePath}")
      assertThat(result.output).contains("Suite result: PASSED")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `directory input runs journey files in sorted filename order`() {
    val dir = createTempDir(prefix = "verity-run-suite")
    try {
      writeJourney(dir, "b.journey.yaml", "Second")
      writeJourney(dir, "a.journey.yaml", "First")
      writeJourney(dir, "ignored.yaml", "Ignored")
      val seen = mutableListOf<String>()
      val command = runCommand { journeys ->
        seen += journeys.map { it.file.name }
        SuiteRunResult(
          journeys.map { resolved ->
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(resolved.journey.name, listOf(SegmentResult(index = 0, passed = true))),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("run ${dir.absolutePath}")

      assertThat(result.statusCode).isEqualTo(0)
      assertThat(seen).containsExactly("a.journey.yaml", "b.journey.yaml")
      assertThat(result.output).contains("Total: 2")
      assertThat(result.output).contains("Passed: 2")
      assertThat(result.output).contains("Failed: 0")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `empty directory returns clear non-zero error`() {
    val dir = createTempDir(prefix = "verity-run-empty")
    try {
      val result = Verity()
        .subcommands(runCommand { error("Suite runner should not be called") })
        .test("run ${dir.absolutePath}")

      assertThat(result.statusCode).isEqualTo(1)
      assertThat(result.output).contains("No journey files found in: ${dir.absolutePath}")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `failing journey output includes file name journey name and failed segment`() {
    val dir = createTempDir(prefix = "verity-run-failure")
    try {
      val file = writeJourney(dir, "failure.journey.yaml", "Failure journey")
      val command = runCommand { journeys ->
        SuiteRunResult(
          journeys.map { resolved ->
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(
                journeyName = resolved.journey.name,
                segments = listOf(
                  SegmentResult(index = 0, passed = true),
                  SegmentResult(index = 1, passed = false, reasoning = "Expected text was missing"),
                ),
              ),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("run ${file.absolutePath}")

      assertThat(result.statusCode).isEqualTo(1)
      assertThat(result.output).contains("File: ${file.absolutePath}")
      assertThat(result.output).contains("Journey: Failure journey")
      assertThat(result.output).contains("FAILED: Segment 1 failed")
      assertThat(result.output).contains("Expected text was missing")
      assertThat(result.output).contains("Suite result: FAILED")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `multiple journeys aggregate final pass fail outcome`() {
    val dir = createTempDir(prefix = "verity-run-aggregate")
    try {
      writeJourney(dir, "pass.journey.yaml", "Passing journey")
      writeJourney(dir, "fail.journey.yaml", "Failing journey")
      val seen = mutableListOf<String>()
      val command = runCommand { journeys ->
        seen += journeys.map { it.file.name }
        SuiteRunResult(
          journeys.map { resolved ->
            val passed = resolved.file.name == "pass.journey.yaml"
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(
                journeyName = resolved.journey.name,
                segments = listOf(SegmentResult(index = 0, passed = passed, reasoning = if (passed) "" else "Failed")),
              ),
            )
          },
        )
      }

      val result = Verity()
        .subcommands(command)
        .test("run ${dir.absolutePath}")

      assertThat(result.statusCode).isEqualTo(1)
      assertThat(seen).containsExactly("fail.journey.yaml", "pass.journey.yaml")
      assertThat(result.output).contains("Total: 2")
      assertThat(result.output).contains("Passed: 1")
      assertThat(result.output).contains("Failed: 1")
      assertThat(result.output).contains("Suite result: FAILED")
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun runCommand(
    runner: suspend (List<ResolvedJourney>) -> SuiteRunResult,
  ): RunCommand = RunCommand(suiteRunner = runner)

  private fun writeJourney(dir: File, name: String, journeyName: String): File {
    val file = File(dir, name)
    file.writeText(
      """
      name: $journeyName
      app: com.example.app
      platform: android-tv

      steps:
        - [?] Home
      """.trimIndent(),
    )
    return file
  }
}
```

- [ ] **Step 2: Run the new test to verify it fails**

Run:

```bash
./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.RunCommandTest
```

Expected: FAIL at compilation because `RunCommand(suiteRunner = ...)`, `ResolvedJourney`, `ResolvedJourneyResult`, and `SuiteRunResult` do not exist yet.

- [ ] **Step 3: Commit the failing test**

```bash
git add verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt
git commit -m "test: cover directory suite runs"
```

---

### Task 2: Add Suite Resolution and Result Types

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`
- Test: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt`

- [ ] **Step 1: Add imports and CLI-owned suite types**

In `RunCommand.kt`, add these imports:

```kotlin
import me.chrisbanes.verity.agent.JourneyResult
import me.chrisbanes.verity.core.model.Journey
```

Then add these declarations above `class RunCommand`:

```kotlin
internal data class ResolvedJourney(
  val file: File,
  val journey: Journey,
)

internal data class ResolvedJourneyResult(
  val resolvedJourney: ResolvedJourney,
  val result: JourneyResult,
)

internal data class SuiteRunResult(
  val results: List<ResolvedJourneyResult>,
) {
  val passed: Boolean get() = results.all { it.result.passed }
  val passedCount: Int get() = results.count { it.result.passed }
  val failedCount: Int get() = results.count { !it.result.passed }
}
```

- [ ] **Step 2: Add injectable constructor parameters**

Change the class declaration from:

```kotlin
class RunCommand : CliktCommand(name = "run") {
```

to:

```kotlin
class RunCommand(
  private val loadJourney: (File) -> Journey = JourneyLoader::fromFile,
  private val listJourneyFiles: (File) -> List<File> = JourneyLoader::listJourneyFiles,
  private val suiteRunner: (suspend (List<ResolvedJourney>) -> SuiteRunResult)? = null,
) : CliktCommand(name = "run") {
```

- [ ] **Step 3: Add path resolution helpers**

Inside `RunCommand`, below the `journeyPath` property, add:

```kotlin
  private fun resolveJourneys(path: File): List<ResolvedJourney> {
    if (!path.exists()) {
      throw CliktError("Journey path not found: ${path.absolutePath}")
    }

    return when {
      path.isDirectory -> {
        val files = listJourneyFiles(path)
        if (files.isEmpty()) {
          throw CliktError("No journey files found in: ${path.absolutePath}")
        }
        files.map { file -> ResolvedJourney(file = file, journey = loadJourney(file)) }
      }

      path.isFile -> listOf(ResolvedJourney(file = path, journey = loadJourney(path)))

      else -> throw CliktError("Journey path is not a file or directory: ${path.absolutePath}")
    }
  }
```

- [ ] **Step 4: Run the new tests**

Run:

```bash
./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.RunCommandTest
```

Expected: FAIL because `RunCommand.run()` still ignores `resolveJourneys(...)` and does not call `suiteRunner`.

- [ ] **Step 5: Commit the suite types and resolver**

```bash
git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt
git commit -m "feat: resolve run inputs as journeys"
```

---

### Task 3: Wire `RunCommand` Through the Suite Runner

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`
- Test: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt`

- [ ] **Step 1: Replace `run()` with suite-aware control flow**

Replace the body of `override fun run() = runBlocking { ... }` with:

```kotlin
  override fun run() = runBlocking {
    val parent = currentContext.parent?.command as Verity

    val path = journeyPath?.let { File(it) }
      ?: throw UsageError("Journey path required. Use: verity run <path>")
    val journeys = resolveJourneys(path)

    val suiteResult = suiteRunner?.invoke(journeys)
      ?: runSuiteWithDevice(parent = parent, journeys = journeys)

    printSuiteResult(suiteResult)

    if (!suiteResult.passed) {
      throw CliktError("Journey suite failed")
    }
  }
```

- [ ] **Step 2: Add console reporting**

Inside `RunCommand`, add:

```kotlin
  private fun printSuiteResult(suiteResult: SuiteRunResult) {
    suiteResult.results.forEachIndexed { index, journeyResult ->
      val resolved = journeyResult.resolvedJourney
      val result = journeyResult.result

      if (index > 0) echo()
      echo("File: ${resolved.file.absolutePath}")
      echo("Journey: ${resolved.journey.name}")
      echo("App: ${resolved.journey.app}")
      echo("Platform: ${resolved.journey.platform}")

      if (result.passed) {
        echo("PASSED: All ${result.segments.size} segments passed")
      } else {
        echo("FAILED: Segment ${result.failedAt} failed")
        result.segments.filter { !it.passed }.forEach { seg ->
          echo("  Segment ${seg.index}: ${seg.reasoning}")
        }
      }
    }

    echo()
    echo("Suite result: ${if (suiteResult.passed) "PASSED" else "FAILED"}")
    echo("Total: ${suiteResult.results.size}")
    echo("Passed: ${suiteResult.passedCount}")
    echo("Failed: ${suiteResult.failedCount}")
  }
```

- [ ] **Step 3: Extract production execution into `runSuiteWithDevice`**

Inside `RunCommand`, add this private function:

```kotlin
  private suspend fun runSuiteWithDevice(
    parent: Verity,
    journeys: List<ResolvedJourney>,
  ): SuiteRunResult {
    val config = VerityConfig.loadOrDefault(File("verity/config.yaml"))
    val provider = resolveProvider(parent.provider, config)

    val apiKey = parent.apiKey ?: System.getenv(provider.envVar)
    if (provider.requiresAuth && apiKey == null) {
      throw UsageError("API key required. Set ${provider.envVar} or use --api-key")
    }

    val navigatorModel = resolveModel(
      cliModel = parent.navigatorModel,
      configModel = config.navigatorModel,
      default = provider.defaultNavigatorModel,
      provider = provider,
    )
    val inspectorModel = resolveModel(
      cliModel = parent.inspectorModel,
      configModel = config.inspectorModel,
      default = provider.defaultInspectorModel,
      provider = provider,
    )

    echo("Provider: ${provider.name}")
    echo("Navigator model: ${navigatorModel.id}")
    echo("Inspector model: ${inspectorModel.id}")

    val firstJourney = journeys.first().journey
    val platform = parent.platform ?: firstJourney.platform
    val session = DeviceSessionFactory.connect(
      platform = platform,
      deviceId = parent.device,
      disableAnimations = parent.noAnimations,
    )

    val executor = SingleLLMPromptExecutor(provider.createClient(apiKey ?: ""))

    session.use {
      val injectedContext = parent.contextPath?.let { ContextLoader.load(File(it)) } ?: ""

      val orchestrator = Orchestrator(
        session = session,
        navigatorFactory = {
          NavigatorAgent(
            bundledContext = if (parent.noBundledContext) "" else ContextLoader.loadBundled(),
            agentFactory = { systemPrompt ->
              AIAgent(
                promptExecutor = executor,
                llmModel = navigatorModel,
                systemPrompt = systemPrompt,
              )
            },
          )
        },
        inspectorFactory = {
          InspectorAgent(
            treeAgentFactory = {
              AIAgent(
                promptExecutor = executor,
                llmModel = inspectorModel,
                systemPrompt = InspectorAgent.SYSTEM_PROMPT,
              )
            },
            evaluateVisualContent = { systemPrompt, userMessage, screenshotPath ->
              val p = prompt("visual-eval") {
                system(systemPrompt)
                user {
                  text(userMessage)
                  image(kotlinx.io.files.Path(screenshotPath.toString()))
                }
              }
              val responses = executor.execute(p, inspectorModel)
              responses.last().content
            },
          )
        },
        context = injectedContext,
      )

      return SuiteRunResult(
        journeys.map { resolved ->
          ResolvedJourneyResult(
            resolvedJourney = resolved,
            result = orchestrator.run(resolved.journey),
          )
        },
      )
    }
  }
```

- [ ] **Step 4: Remove obsolete single-file execution code**

After adding `runSuiteWithDevice`, delete the old inline code in `run()` that loaded one file, connected a session, ran one journey, and printed only one result. `RunCommand.kt` should have exactly one `override fun run()` implementation and no old `val file = ...` block.

- [ ] **Step 5: Run the focused CLI tests**

Run:

```bash
./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.RunCommandTest
```

Expected: PASS.

- [ ] **Step 6: Commit suite runner wiring**

```bash
git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt
git commit -m "feat: run journey directories as suites"
```

---

### Task 4: Update Architecture Documentation

**Files:**
- Modify: `docs/architecture.md`
- Test: none

- [ ] **Step 1: Update the CLI command list**

In `docs/architecture.md`, change:

```markdown
verity run <journey>           Execute a journey autonomously
```

to:

```markdown
verity run <path>              Execute a journey file or directory suite autonomously
```

- [ ] **Step 2: Update the CLI run data flow**

In the `### CLI Run` data-flow section, replace:

```markdown
Journey YAML
    │
    ▼
JourneyLoader.load() ──→ Journey(name, app, platform, steps)
```

with:

```markdown
File or directory path
    │
    ▼
RunCommand.resolveJourneys() ──→ ordered List<ResolvedJourney>
    │
    ▼
JourneyLoader.fromFile() ──→ Journey(name, app, platform, steps)
```

Then add this sentence after the diagram:

```markdown
When the input is a directory, `verity run` discovers non-recursive `*.journey.yaml` files in deterministic filename order, executes every journey, and returns a failed process outcome if any journey fails.
```

- [ ] **Step 3: Run docs diff check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: Commit architecture docs**

```bash
git add docs/architecture.md
git commit -m "docs: describe directory suite runs"
```

---

### Task 5: Run Formatting and Full Verification

**Files:**
- Modify: any files changed by Spotless
- Test: all changed behavior

- [ ] **Step 1: Apply formatting**

Run:

```bash
./gradlew spotlessApply
```

Expected: SUCCESS.

- [ ] **Step 2: Run focused tests**

Run:

```bash
./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.RunCommandTest
```

Expected: PASS.

- [ ] **Step 3: Run full checks**

Run:

```bash
./gradlew check
```

Expected: PASS.

- [ ] **Step 4: Inspect final diff**

Run:

```bash
git status --short
git diff --stat
```

Expected: only intended files are changed if Spotless adjusted formatting after the previous commits.

- [ ] **Step 5: Commit any final formatting changes**

If `git status --short` shows changes after `spotlessApply`, run:

```bash
git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt docs/architecture.md
git commit -m "style: format directory suite changes"
```

If `git status --short` is clean, do not create an empty commit.

---

## Self-Review

Spec coverage:

- Single-file behavior is preserved by resolving file input to a one-item `List<ResolvedJourney>` and still calling `Orchestrator.run(journey)`.
- Directory behavior is covered by `JourneyLoader.listJourneyFiles(directory)`, sorted execution tests, and suite loop wiring.
- Empty-directory failure is covered by `resolveJourneys(...)` and a CLI-level non-zero test.
- Failure output includes source file, journey name, failed segment, and segment reasoning.
- Aggregate final pass/fail behavior is covered by a multi-journey CLI test.
- #49 artifact persistence and JSON results are not introduced.

Placeholder scan:

- No TBD/TODO/later placeholders.
- Every code-edit step includes concrete code.
- Every verification step includes exact commands and expected outcomes.

Type consistency:

- `ResolvedJourney`, `ResolvedJourneyResult`, and `SuiteRunResult` are introduced before tests rely on them passing.
- `SuiteRunResult.passed`, `passedCount`, and `failedCount` match the reporting code.
- `suiteRunner` signature matches the tests and `RunCommand.run()` invocation.
