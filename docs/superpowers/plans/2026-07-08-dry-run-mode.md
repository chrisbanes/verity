# Dry-Run Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `verity run --dry-run` so journey authors can inspect parsing, segmentation, fast-path classification, and generated slow-path Maestro YAML without device access.

**Architecture:** Keep dry-run as a CLI-owned planning path, separate from `Orchestrator` and `DeviceSession`. `RunCommand` resolves journeys exactly as normal, then dry-run builds a report with `JourneySegmenter`, `InteractionMapper`, lazy navigator generation, a Markdown renderer, and an artifact writer.

**Tech Stack:** Kotlin/JVM 21, Clikt, kotlinx-coroutines, Koog for navigator generation, assertk, kotlinx-coroutines-test, Gradle via `./gradlew`.

---

## File Structure

- Create `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunPlanner.kt`: dry-run report models, planner, navigator abstraction, and interaction descriptions.
- Create `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunRenderer.kt`: one Markdown renderer shared by stdout and artifact files.
- Create `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunArtifactWriter.kt`: writes one Markdown file per journey under `<output-path>/dry-run` using `Dispatchers.IO`.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/CliPreflightChecker.kt`: allow provider/model/path/temp preflight without device preflight.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`: add `--dry-run`, branch after journey resolution, load context, lazily preflight provider/model only when navigator YAML is needed, print reports, and write artifacts.
- Modify `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt`: cover skipped device preflight.
- Create `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunPlannerTest.kt`: cover fast path, slow path, loops, assertions, and lazy navigator behavior.
- Create `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunArtifactWriterTest.kt`: cover artifact path and content.
- Modify `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt`: cover CLI dry-run behavior and no normal suite runner call.
- Modify `docs/architecture.md`: document `verity run --dry-run`.

### Task 1: Add Device-Preflight Skip Support

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/CliPreflightChecker.kt`
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt`

- [ ] **Step 1: Write the failing test**

Add this test to `CliPreflightCheckerTest` after `runs device preflight for selected platform`:

```kotlin
@Test
fun `can skip device preflight for dry run`() = runTest {
  val journey = Files.createTempFile("journey-", ".journey.yaml")
  var devicePreflightCalls = 0
  val checker = CliPreflightChecker(
    environment = { name -> if (name == "ANTHROPIC_API_KEY") "secret" else null },
    devicePreflightChecker = DevicePreflightChecker { _, _ ->
      devicePreflightCalls += 1
      PreflightReport()
    },
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
    includeDevicePreflight = false,
  )

  assertThat(result.report.passed).isTrue()
  assertThat(devicePreflightCalls).isEqualTo(0)
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.CliPreflightCheckerTest`

Expected: compilation fails because `check` has no `includeDevicePreflight` parameter.

- [ ] **Step 3: Implement the minimal preflight flag**

In `CliPreflightChecker.kt`, change the `check` signature and device preflight line to:

```kotlin
suspend fun check(
  request: CliPreflightRequest,
  config: VerityConfig,
  includeDevicePreflight: Boolean = true,
): CliPreflightResult {
```

Replace:

```kotlin
report += devicePreflightChecker.check(request.platform, request.deviceId)
```

with:

```kotlin
if (includeDevicePreflight) {
  report += devicePreflightChecker.check(request.platform, request.deviceId)
}
```

- [ ] **Step 4: Run the targeted test to verify it passes**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.CliPreflightCheckerTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/CliPreflightChecker.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt
git commit -m "feat: allow CLI preflight without device checks"
```

### Task 2: Add Dry-Run Planner Models And Fast-Path Planning

**Files:**
- Create: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunPlanner.kt`
- Create: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunPlannerTest.kt`

- [ ] **Step 1: Write failing planner tests for fast-path-only journeys**

Create `DryRunPlannerTest.kt` with:

```kotlin
package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform

class DryRunPlannerTest {
  @Test
  fun `fast path actions are represented without invoking navigator`() = runTest {
    var navigatorCalls = 0
    val planner = DryRunPlanner(
      navigatorFactory = {
        navigatorCalls += 1
        DryRunNavigator { _, _, _, _ -> error("navigator should not be called") }
      },
    )
    val journey = Journey(
      name = "Fast journey",
      app = "com.example.app",
      platform = Platform.ANDROID_TV,
      steps = listOf(
        JourneyStep.Action("press d-pad down"),
        JourneyStep.Action("press select"),
        JourneyStep.Assert("Home", AssertMode.VISIBLE),
      ),
    )

    val report = planner.plan(resolvedJourney(journey))

    assertThat(navigatorCalls).isEqualTo(0)
    assertThat(report.launchYaml).isEqualTo("appId: com.example.app\n---\n- launchApp")
    assertThat(report.segments).transform { it.size }.isEqualTo(1)
    val segment = report.segments.single()
    assertThat(segment.index).isEqualTo(0)
    assertThat(segment.actions?.kind).isEqualTo(DryRunExecutionKind.FAST_PATH)
    assertThat(segment.actions?.instructions).containsExactly("press d-pad down", "press select")
    assertThat(segment.actions?.interactions.orEmpty()).contains("KeyPress(DPAD_DOWN)")
    assertThat(segment.actions?.yaml).isNull()
    assertThat(segment.assertion?.description).isEqualTo("Home")
    assertThat(segment.assertion?.mode).isEqualTo(AssertMode.VISIBLE)
  }

  private fun resolvedJourney(journey: Journey): ResolvedJourney = ResolvedJourney(
    file = java.io.File("${journey.name}.journey.yaml"),
    journey = journey,
  )
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.DryRunPlannerTest`

Expected: compilation fails because dry-run planner types do not exist.

- [ ] **Step 3: Implement planner models and fast-path planning**

Create `DryRunPlanner.kt`:

```kotlin
package me.chrisbanes.verity.cli

import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.interaction.InteractionMapper
import me.chrisbanes.verity.core.journey.JourneySegmenter
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneySegment
import me.chrisbanes.verity.core.model.Platform

fun interface DryRunNavigator {
  suspend fun generate(
    actions: List<String>,
    appId: String,
    platform: Platform,
    context: String,
  ): String
}

enum class DryRunExecutionKind {
  FAST_PATH,
  SLOW_PATH,
}

data class DryRunSuiteReport(
  val journeys: List<DryRunJourneyReport>,
)

data class DryRunJourneyReport(
  val resolvedJourney: ResolvedJourney,
  val launchYaml: String,
  val segments: List<DryRunSegmentReport>,
  val artifactFile: java.io.File? = null,
)

data class DryRunSegmentReport(
  val index: Int,
  val actions: DryRunActionGroupReport? = null,
  val loop: DryRunLoopReport? = null,
  val assertion: DryRunAssertionReport? = null,
)

data class DryRunActionGroupReport(
  val instructions: List<String>,
  val kind: DryRunExecutionKind,
  val interactions: List<String> = emptyList(),
  val yaml: String? = null,
)

data class DryRunLoopReport(
  val action: String,
  val until: String,
  val max: Int,
  val kind: DryRunExecutionKind,
  val interaction: String? = null,
  val yaml: String? = null,
)

data class DryRunAssertionReport(
  val description: String,
  val mode: AssertMode,
)

class DryRunPlanner(
  private val navigatorFactory: suspend () -> DryRunNavigator,
  private val context: String = "",
) {
  private var navigator: DryRunNavigator? = null

  suspend fun plan(resolvedJourney: ResolvedJourney): DryRunJourneyReport {
    val journey = resolvedJourney.journey
    return DryRunJourneyReport(
      resolvedJourney = resolvedJourney,
      launchYaml = launchYaml(journey),
      segments = JourneySegmenter.segment(journey.steps).map { segment -> planSegment(segment, journey) },
    )
  }

  private suspend fun planSegment(segment: JourneySegment, journey: Journey): DryRunSegmentReport = DryRunSegmentReport(
    index = segment.index,
    actions = planActions(segment.actions.map { it.instruction }, journey),
    loop = segment.loop?.let { loop ->
      val mapper = InteractionMapper.forPlatform(journey.platform)
      val interaction = mapper.map(loop.action)
      if (interaction != null) {
        DryRunLoopReport(
          action = loop.action,
          until = loop.until,
          max = loop.max,
          kind = DryRunExecutionKind.FAST_PATH,
          interaction = describeInteraction(interaction),
        )
      } else {
        DryRunLoopReport(
          action = loop.action,
          until = loop.until,
          max = loop.max,
          kind = DryRunExecutionKind.SLOW_PATH,
          yaml = navigator().generate(listOf(loop.action), journey.app, journey.platform, context),
        )
      }
    },
    assertion = segment.assertion?.let { DryRunAssertionReport(it.description, it.mode) },
  )

  private suspend fun planActions(instructions: List<String>, journey: Journey): DryRunActionGroupReport? {
    if (instructions.isEmpty()) return null

    val mapper = InteractionMapper.forPlatform(journey.platform)
    val interactions = instructions.map { mapper.map(it) }
    return if (interactions.all { it != null }) {
      DryRunActionGroupReport(
        instructions = instructions,
        kind = DryRunExecutionKind.FAST_PATH,
        interactions = interactions.filterNotNull().map(::describeInteraction),
      )
    } else {
      DryRunActionGroupReport(
        instructions = instructions,
        kind = DryRunExecutionKind.SLOW_PATH,
        yaml = navigator().generate(instructions, journey.app, journey.platform, context),
      )
    }
  }

  private suspend fun navigator(): DryRunNavigator = navigator ?: navigatorFactory().also { navigator = it }

  private fun launchYaml(journey: Journey): String = "appId: ${journey.app}\n---\n- launchApp"

  private fun describeInteraction(interaction: Interaction): String = when (interaction) {
    is Interaction.KeyPress -> "KeyPress(${interaction.keyName})"
    is Interaction.TapOnText -> "TapOnText(${interaction.text})"
    is Interaction.TapOnId -> "TapOnId(${interaction.resourceId})"
    is Interaction.Scroll -> "Scroll(${interaction.direction})"
    is Interaction.Swipe -> "Swipe(${interaction.direction})"
    Interaction.LongPressOnFocused -> "LongPressOnFocused"
    is Interaction.LongPressOnText -> "LongPressOnText(${interaction.text})"
    Interaction.PullToRefresh -> "PullToRefresh"
  }
}
```

- [ ] **Step 4: Run the targeted test to verify it passes**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.DryRunPlannerTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunPlanner.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunPlannerTest.kt
git commit -m "feat: add dry-run planner fast path reports"
```

### Task 3: Add Slow-Path And Loop Planner Coverage

**Files:**
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunPlannerTest.kt`

- [ ] **Step 1: Add slow-path and loop tests**

Append these tests inside `DryRunPlannerTest` before the helper function:

```kotlin
@Test
fun `slow path actions include generated yaml`() = runTest {
  val planner = DryRunPlanner(
    navigatorFactory = {
      DryRunNavigator { actions, appId, platform, context ->
        assertThat(actions).containsExactly("complete onboarding wizard")
        assertThat(appId).isEqualTo("com.example.app")
        assertThat(platform).isEqualTo(Platform.ANDROID_MOBILE)
        assertThat(context).isEqualTo("project context")
        "appId: com.example.app\n---\n- tapOn: \"Settings\""
      }
    },
    context = "project context",
  )
  val journey = Journey(
    name = "Slow journey",
    app = "com.example.app",
    platform = Platform.ANDROID_MOBILE,
    steps = listOf(JourneyStep.Action("complete onboarding wizard")),
  )

  val segment = planner.plan(resolvedJourney(journey)).segments.single()

  assertThat(segment.actions?.kind).isEqualTo(DryRunExecutionKind.SLOW_PATH)
  assertThat(segment.actions?.yaml).isEqualTo("appId: com.example.app\n---\n- tapOn: \"Settings\"")
}

@Test
fun `loop reports fast path mapping when action maps`() = runTest {
  val planner = DryRunPlanner(
    navigatorFactory = { DryRunNavigator { _, _, _, _ -> error("navigator should not be called") } },
  )
  val journey = Journey(
    name = "Loop journey",
    app = "com.example.app",
    platform = Platform.ANDROID_TV,
    steps = listOf(JourneyStep.Loop(action = "press d-pad down", until = "Settings", max = 3)),
  )

  val loop = planner.plan(resolvedJourney(journey)).segments.single().loop

  assertThat(loop?.kind).isEqualTo(DryRunExecutionKind.FAST_PATH)
  assertThat(loop?.action).isEqualTo("press d-pad down")
  assertThat(loop?.until).isEqualTo("Settings")
  assertThat(loop?.max).isEqualTo(3)
  assertThat(loop?.interaction).isEqualTo("KeyPress(DPAD_DOWN)")
  assertThat(loop?.yaml).isNull()
}

@Test
fun `loop reports generated yaml when action does not map`() = runTest {
  val planner = DryRunPlanner(
    navigatorFactory = {
      DryRunNavigator { actions, _, _, _ ->
        assertThat(actions).containsExactly("navigate to settings page")
        "appId: com.example.app\n---\n- tapOn: \"Settings\""
      }
    },
  )
  val journey = Journey(
    name = "Slow loop journey",
    app = "com.example.app",
    platform = Platform.ANDROID_MOBILE,
    steps = listOf(JourneyStep.Loop(action = "navigate to settings page", until = "Settings", max = 2)),
  )

  val loop = planner.plan(resolvedJourney(journey)).segments.single().loop

  assertThat(loop?.kind).isEqualTo(DryRunExecutionKind.SLOW_PATH)
  assertThat(loop?.yaml).isEqualTo("appId: com.example.app\n---\n- tapOn: \"Settings\"")
}
```

- [ ] **Step 2: Run the targeted tests**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.DryRunPlannerTest`

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunPlannerTest.kt
git commit -m "test: cover dry-run slow path planning"
```

### Task 4: Add Markdown Renderer

**Files:**
- Create: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunRenderer.kt`
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunPlannerTest.kt`

- [ ] **Step 1: Add renderer test**

Add this test inside `DryRunPlannerTest`:

```kotlin
@Test
fun `renderer includes launch yaml segments fast path slow path loops and assertions`() = runTest {
  val report = DryRunJourneyReport(
    resolvedJourney = resolvedJourney(
      Journey(
        name = "Rendered journey",
        app = "com.example.app",
        platform = Platform.ANDROID_MOBILE,
        steps = emptyList(),
      ),
    ),
    launchYaml = "appId: com.example.app\n---\n- launchApp",
    segments = listOf(
      DryRunSegmentReport(
        index = 0,
        actions = DryRunActionGroupReport(
          instructions = listOf("tap Settings"),
          kind = DryRunExecutionKind.FAST_PATH,
          interactions = listOf("TapOnText(Settings)"),
        ),
        assertion = DryRunAssertionReport("Settings", AssertMode.VISIBLE),
      ),
      DryRunSegmentReport(
        index = 1,
        actions = DryRunActionGroupReport(
          instructions = listOf("open account details"),
          kind = DryRunExecutionKind.SLOW_PATH,
          yaml = "appId: com.example.app\n---\n- tapOn: \"Account\"",
        ),
        loop = DryRunLoopReport(
          action = "navigate to logout",
          until = "Logout",
          max = 2,
          kind = DryRunExecutionKind.SLOW_PATH,
          yaml = "appId: com.example.app\n---\n- scroll",
        ),
      ),
    ),
  )

  val markdown = DryRunRenderer.renderJourney(report)

  assertThat(markdown).contains("# Dry Run: Rendered journey")
  assertThat(markdown).contains("File: Rendered journey.journey.yaml")
  assertThat(markdown).contains("Platform: ANDROID_MOBILE")
  assertThat(markdown).contains("## Launch Flow")
  assertThat(markdown).contains("```yaml\nappId: com.example.app\n---\n- launchApp\n```")
  assertThat(markdown).contains("## Segment 0")
  assertThat(markdown).contains("Kind: FAST_PATH")
  assertThat(markdown).contains("TapOnText(Settings)")
  assertThat(markdown).contains("Assertion: [VISIBLE] Settings")
  assertThat(markdown).contains("Kind: SLOW_PATH")
  assertThat(markdown).contains("Loop: navigate to logout until Logout, max 2")
  assertThat(markdown).contains("- scroll")
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.DryRunPlannerTest`

Expected: compilation fails because `DryRunRenderer` does not exist.

- [ ] **Step 3: Implement renderer**

Create `DryRunRenderer.kt`:

```kotlin
package me.chrisbanes.verity.cli

object DryRunRenderer {
  fun renderJourney(report: DryRunJourneyReport): String = buildString {
    val resolved = report.resolvedJourney
    val journey = resolved.journey
    appendLine("# Dry Run: ${journey.name}")
    appendLine()
    appendLine("File: ${resolved.file.path}")
    appendLine("App: ${journey.app}")
    appendLine("Platform: ${journey.platform}")
    report.artifactFile?.let { appendLine("Artifact: ${it.path}") }
    appendLine()
    appendLine("## Launch Flow")
    appendYaml(report.launchYaml)
    report.segments.forEach { segment ->
      appendLine()
      appendLine("## Segment ${segment.index}")
      segment.actions?.let { actions ->
        appendLine()
        appendLine("Actions:")
        actions.instructions.forEach { appendLine("- $it") }
        appendLine("Kind: ${actions.kind}")
        if (actions.interactions.isNotEmpty()) {
          appendLine("Interactions:")
          actions.interactions.forEach { appendLine("- $it") }
        }
        actions.yaml?.let {
          appendLine("Generated YAML:")
          appendYaml(it)
        }
      }
      segment.loop?.let { loop ->
        appendLine()
        appendLine("Loop: ${loop.action} until ${loop.until}, max ${loop.max}")
        appendLine("Kind: ${loop.kind}")
        loop.interaction?.let { appendLine("Interaction: $it") }
        loop.yaml?.let {
          appendLine("Generated Loop YAML:")
          appendYaml(it)
        }
      }
      segment.assertion?.let { assertion ->
        appendLine()
        appendLine("Assertion: [${assertion.mode}] ${assertion.description}")
      }
    }
  }.trimEnd()

  fun renderSuite(report: DryRunSuiteReport): String = report.journeys
    .joinToString(separator = "\n\n---\n\n") { renderJourney(it) }

  private fun StringBuilder.appendYaml(yaml: String) {
    appendLine("```yaml")
    appendLine(yaml.trimEnd())
    appendLine("```")
  }
}
```

- [ ] **Step 4: Run the targeted test to verify it passes**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.DryRunPlannerTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunRenderer.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunPlannerTest.kt
git commit -m "feat: render dry-run reports"
```

### Task 5: Add Artifact Writer

**Files:**
- Create: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunArtifactWriter.kt`
- Create: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunArtifactWriterTest.kt`

- [ ] **Step 1: Write failing artifact test**

Create `DryRunArtifactWriterTest.kt`:

```kotlin
package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.endsWith
import assertk.assertions.isEqualTo
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.Platform

class DryRunArtifactWriterTest {
  @Test
  fun `writes one markdown artifact per journey under dry run directory`() = runTest {
    val output = createTempDirectory("verity-dry-run-output").toFile()
    try {
      val journey = Journey(
        name = "Artifact journey",
        app = "com.example.app",
        platform = Platform.ANDROID_TV,
        steps = emptyList(),
      )
      val report = DryRunJourneyReport(
        resolvedJourney = ResolvedJourney(File("artifact.journey.yaml"), journey),
        launchYaml = "appId: com.example.app\n---\n- launchApp",
        segments = emptyList(),
      )

      val suite = DryRunArtifactWriter().write(output, DryRunSuiteReport(listOf(report)))

      val written = suite.journeys.single().artifactFile
      assertThat(written?.path.orEmpty()).endsWith("dry-run/artifact.md")
      assertThat(written?.readText().orEmpty()).contains("# Dry Run: Artifact journey")
      assertThat(written?.readText().orEmpty()).contains("Artifact: ${written?.path}")
      assertThat(suite.journeys.single().resolvedJourney).isEqualTo(report.resolvedJourney)
    } finally {
      output.deleteRecursively()
    }
  }
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.DryRunArtifactWriterTest`

Expected: compilation fails because `DryRunArtifactWriter` does not exist.

- [ ] **Step 3: Implement artifact writer**

Create `DryRunArtifactWriter.kt`:

```kotlin
package me.chrisbanes.verity.cli

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DryRunArtifactWriter {
  suspend fun write(
    outputPath: File,
    suiteReport: DryRunSuiteReport,
  ): DryRunSuiteReport = withContext(Dispatchers.IO) {
    val directory = File(outputPath, "dry-run")
    directory.mkdirs()

    DryRunSuiteReport(
      journeys = suiteReport.journeys.map { report ->
        val file = File(directory, artifactName(report.resolvedJourney.file))
        val reportWithArtifact = report.copy(artifactFile = file)
        file.writeText(DryRunRenderer.renderJourney(reportWithArtifact))
        reportWithArtifact
      },
    )
  }

  private fun artifactName(file: File): String {
    val name = file.name
    return when {
      name.endsWith(".journey.yaml") -> name.removeSuffix(".journey.yaml") + ".md"
      name.endsWith(".yaml") -> name.removeSuffix(".yaml") + ".md"
      else -> file.nameWithoutExtension + ".md"
    }
  }
}
```

- [ ] **Step 4: Run the targeted test to verify it passes**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.DryRunArtifactWriterTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunArtifactWriter.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunArtifactWriterTest.kt
git commit -m "feat: write dry-run artifacts"
```

### Task 6: Wire Dry-Run Into RunCommand With Injectable Test Boundary

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt`

- [ ] **Step 1: Add CLI tests for dry-run branch and artifact output**

In `RunCommandTest.kt`, add imports:

```kotlin
import assertk.assertions.doesNotContain
import assertk.assertions.exists
import me.chrisbanes.verity.core.model.Platform
```

Add this helper after the existing `runCommand` helper:

```kotlin
private fun dryRunCommand(
  runner: suspend (List<ResolvedJourney>, File) -> DryRunSuiteReport,
): RunCommand = RunCommand(dryRunSuiteRunner = runner)
```

Add these tests before the helper functions:

```kotlin
@Test
fun `dry run uses dry-run runner and does not call normal suite runner`() {
  val dir = createTempDirectory("verity-run-dry").toFile()
  try {
    val output = File(dir, "out")
    val file = writeJourney(dir, "dry.journey.yaml", "Dry journey")
    var dryRunCalled = false
    val command = RunCommand(
      suiteRunner = { error("normal suite runner should not be called") },
      dryRunSuiteRunner = { journeys, outputPath ->
        dryRunCalled = true
        assertThat(journeys.map { it.file.name }).containsExactly("dry.journey.yaml")
        assertThat(outputPath).isEqualTo(output)
        DryRunSuiteReport(
          journeys = listOf(
            DryRunJourneyReport(
              resolvedJourney = journeys.single(),
              launchYaml = "appId: com.example.app\n---\n- launchApp",
              segments = emptyList(),
              artifactFile = File(output, "dry-run/dry.md"),
            ),
          ),
        )
      },
    )

    val result = Verity()
      .subcommands(command)
      .test(listOf("--output-path", output.absolutePath, "run", "--dry-run", file.absolutePath))

    assertThat(result.statusCode).isEqualTo(0)
    assertThat(dryRunCalled).isEqualTo(true)
    assertThat(result.output).contains("# Dry Run: Dry journey")
    assertThat(result.output).contains("Artifact: ${File(output, "dry-run/dry.md").path}")
    assertThat(result.output).doesNotContain("Suite result:")
  } finally {
    dir.deleteRecursively()
  }
}

@Test
fun `dry run writes artifacts with production dry-run runner`() {
  val dir = createTempDirectory("verity-run-dry-artifact").toFile()
  try {
    val output = File(dir, "out")
    val file = writeJourneyWithSteps(
      dir = dir,
      name = "fast.journey.yaml",
      journeyName = "Fast dry journey",
      steps = listOf("press d-pad down", "[?] Home"),
    )

    val result = Verity()
      .subcommands(RunCommand())
      .test(listOf("--output-path", output.absolutePath, "run", "--dry-run", file.absolutePath))

    val artifact = File(output, "dry-run/fast.md")
    assertThat(result.statusCode).isEqualTo(0)
    assertThat(artifact).exists()
    assertThat(artifact.readText()).contains("# Dry Run: Fast dry journey")
    assertThat(result.output).contains("KeyPress(DPAD_DOWN)")
    assertThat(result.output).contains("Assertion: [VISIBLE] Home")
  } finally {
    dir.deleteRecursively()
  }
}
```

Add this helper after `writeJourney`:

```kotlin
private fun writeJourneyWithSteps(
  dir: File,
  name: String,
  journeyName: String,
  platform: String = "android-tv",
  steps: List<String>,
): File {
  val file = File(dir, name)
  file.writeText(
    buildString {
      appendLine("name: $journeyName")
      appendLine("app: com.example.app")
      appendLine("platform: $platform")
      appendLine()
      appendLine("steps:")
      steps.forEach { step -> appendLine("  - \"${step.replace("\\", "\\\\").replace("\"", "\\\"")}\"") }
    },
  )
  return file
}
```

- [ ] **Step 2: Run targeted tests to verify they fail**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.RunCommandTest`

Expected: compilation fails because `dryRunSuiteRunner`, `--dry-run`, and production dry-run wiring do not exist.

- [ ] **Step 3: Add constructor seam and option in RunCommand**

In `RunCommand.kt`, add imports:

```kotlin
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
```

Change the constructor to:

```kotlin
class RunCommand(
  private val loadJourney: (File, AssertionStrategy) -> Journey = JourneyLoader::fromFile,
  private val listJourneyFiles: (File) -> List<File> = JourneyLoader::listJourneyFiles,
  private val suiteRunner: (suspend (List<ResolvedJourney>) -> SuiteRunResult)? = null,
  private val dryRunSuiteRunner: (suspend (List<ResolvedJourney>, File) -> DryRunSuiteReport)? = null,
) : CliktCommand(name = "run") {
```

Add the option near `journeyPath`:

```kotlin
private val dryRun by option("--dry-run", help = "Validate journeys and render generated Maestro YAML without device access").flag()
```

- [ ] **Step 4: Branch to dry-run in `run()`**

In `RunCommand.run()`, after `journeys` is resolved and before `suiteRunner?.invoke(journeys)`, add:

```kotlin
if (dryRun) {
  val dryRunReport = dryRunSuiteRunner?.invoke(journeys, resolved.outputPath)
    ?: runDryRun(
      parent = parent,
      config = config,
      resolved = resolved,
      path = path,
      journeys = journeys,
    )
  echo(DryRunRenderer.renderSuite(dryRunReport))
  return@runBlocking
}
```

- [ ] **Step 5: Add production dry-run method with lazy navigator preflight**

Add this method inside `RunCommand` before `printSuiteResult`:

```kotlin
private suspend fun runDryRun(
  parent: Verity,
  config: VerityConfig,
  resolved: ResolvedProjectConfig,
  path: File,
  journeys: List<ResolvedJourney>,
): DryRunSuiteReport {
  val contextDir = resolved.contextPath
  val requireContext = resolveRequiredContext(parent.requireContext, config)
  val projectContext = try {
    withContext(Dispatchers.IO) {
      ContextLoader.loadProject(directory = contextDir, required = requireContext)
    }
  } catch (e: ContextValidationException) {
    throw CliktError(e.message ?: "Project context validation failed")
  }
  projectContext.describeForCli(contextDir, requireContext).forEach { echo(it) }

  var dryRunNavigator: DryRunNavigator? = null
  val planner = DryRunPlanner(
    context = projectContext.text,
    navigatorFactory = {
      dryRunNavigator ?: createDryRunNavigator(parent, config, resolved, path, journeys.first().journey.platform)
        .also { dryRunNavigator = it }
    },
  )
  val suiteReport = DryRunSuiteReport(journeys.map { resolvedJourney -> planner.plan(resolvedJourney) })
  return DryRunArtifactWriter().write(resolved.outputPath, suiteReport)
}
```

Add this method after `runDryRun`:

```kotlin
private suspend fun createDryRunNavigator(
  parent: Verity,
  config: VerityConfig,
  resolved: ResolvedProjectConfig,
  path: File,
  platform: Platform,
): DryRunNavigator {
  val preflight = CliPreflightChecker().check(
    request = CliPreflightRequest(
      cliProvider = parent.provider,
      cliNavigatorModel = parent.navigatorModel,
      cliInspectorModel = parent.inspectorModel,
      apiKey = parent.apiKey,
      journeyPath = path.path,
      contextPath = null,
      platform = platform,
      deviceId = resolved.deviceId,
    ),
    config = config,
    includeDevicePreflight = false,
  )
  if (!preflight.report.passed) {
    throw CliktError(preflight.report.renderPlainText())
  }

  val provider = checkNotNull(preflight.provider)
  val navigatorModel = checkNotNull(preflight.navigatorModel)
  val executor = MultiLLMPromptExecutor(provider.createClient(preflight.apiKey.orEmpty()))
  val navigatorAgent = NavigatorAgent(
    bundledContext = if (parent.noBundledContext) "" else ContextLoader.loadBundled(),
    agentFactory = { systemPrompt ->
      AIAgent(
        promptExecutor = executor,
        llmModel = navigatorModel,
        systemPrompt = systemPrompt,
      )
    },
  )
  return DryRunNavigator { actions, appId, targetPlatform, context ->
    try {
      navigatorAgent.generate(actions, appId, targetPlatform, context)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      throw e
    } catch (e: Exception) {
      throw CliktError("Dry-run YAML generation failed for ${actions.joinToString()}: ${e.message}")
    }
  }
}
```

- [ ] **Step 6: Run targeted tests to verify they pass**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.RunCommandTest`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt
git commit -m "feat: wire dry-run into run command"
```

### Task 7: Add Invalid Journey And Directory Dry-Run Tests

**Files:**
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt`

- [ ] **Step 1: Add acceptance tests for parser errors and directory behavior**

Add these tests to `RunCommandTest.kt`:

```kotlin
@Test
fun `dry run invalid journey fails before dry-run runner`() {
  val dir = createTempDirectory("verity-run-dry-invalid").toFile()
  try {
    val file = File(dir, "invalid.journey.yaml")
    file.writeText("name: Invalid\nsteps: not-a-list")

    val result = Verity()
      .subcommands(dryRunCommand { _, _ -> error("dry-run runner should not be called") })
      .test("run --dry-run ${file.absolutePath}")

    assertThat(result.statusCode).isEqualTo(1)
    assertThat(result.output).contains("invalid.journey.yaml")
  } finally {
    dir.deleteRecursively()
  }
}

@Test
fun `directory dry run resolves files in sorted order`() {
  val dir = createTempDirectory("verity-run-dry-directory").toFile()
  try {
    writeJourney(dir, "b.journey.yaml", "Second")
    writeJourney(dir, "a.journey.yaml", "First")
    val seen = mutableListOf<String>()

    val result = Verity()
      .subcommands(dryRunCommand { journeys, _ ->
        seen += journeys.map { it.file.name }
        DryRunSuiteReport(
          journeys.map { resolved ->
            DryRunJourneyReport(
              resolvedJourney = resolved,
              launchYaml = "appId: ${resolved.journey.app}\n---\n- launchApp",
              segments = emptyList(),
            )
          },
        )
      })
      .test("run --dry-run ${dir.absolutePath}")

    assertThat(result.statusCode).isEqualTo(0)
    assertThat(seen).containsExactly("a.journey.yaml", "b.journey.yaml")
    assertThat(result.output).contains("# Dry Run: First")
    assertThat(result.output).contains("# Dry Run: Second")
  } finally {
    dir.deleteRecursively()
  }
}

@Test
fun `directory dry run rejects mixed platforms`() {
  val dir = createTempDirectory("verity-run-dry-mixed").toFile()
  try {
    writeJourney(dir, "android.journey.yaml", "Android journey", platform = "android-tv")
    writeJourney(dir, "ios.journey.yaml", "iOS journey", platform = "ios")

    val result = Verity()
      .subcommands(dryRunCommand { _, _ -> error("dry-run runner should not be called") })
      .test("run --dry-run ${dir.absolutePath}")

    assertThat(result.statusCode).isEqualTo(1)
    assertThat(result.output).contains("Directory suites must use a single platform")
  } finally {
    dir.deleteRecursively()
  }
}
```

- [ ] **Step 2: Run targeted tests**

Run: `./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.RunCommandTest`

Expected: PASS. If the invalid parser message lacks the filename, wrap `loadJourney` failures in `resolveJourney` with `CliktError("Failed to load journey ${file.absolutePath}: ${e.message}")` while rethrowing `CancellationException` first if needed in suspend code.

- [ ] **Step 3: Commit**

```bash
git add verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt
git commit -m "test: cover dry-run CLI acceptance cases"
```

### Task 8: Update Architecture Documentation

**Files:**
- Modify: `docs/architecture.md`

- [ ] **Step 1: Update CLI mode description**

In `docs/architecture.md`, change line 5 from:

```markdown
1. **CLI mode** (`verity run`) — Executes journey YAML files against a connected device, using LLMs to generate Maestro flows and evaluate assertions.
```

to:

```markdown
1. **CLI mode** (`verity run`) — Executes journey YAML files against a connected device, using LLMs to generate Maestro flows and evaluate assertions. `verity run --dry-run` parses, segments, renders fast-path actions, and generates slow-path Maestro YAML without device access.
```

- [ ] **Step 2: Add dry-run details near CLI run architecture**

After the segmentation section ending with `Each segment is a natural checkpoint: run actions, evaluate assertion, stop on failure.`, add:

```markdown
### Dry-Run Planning

`verity run --dry-run` reuses normal journey resolution and segmentation, then switches to a CLI-owned planner instead of `Orchestrator`. The planner renders the launch flow, classifies fast-path action groups with `InteractionMapper`, generates Maestro YAML for slow-path action groups with `NavigatorAgent`, and records assertion descriptions and modes without evaluating them.

Dry-run always prints a Markdown report and writes per-journey artifacts under `<output-path>/dry-run`. It may call the navigator LLM for slow-path YAML, but it does not run device preflight, create `DeviceSession`, execute flows, capture hierarchy, capture screenshots, or evaluate assertions.
```

- [ ] **Step 3: Commit**

```bash
git add docs/architecture.md
git commit -m "docs: describe run dry-run mode"
```

### Task 9: Formatting And Full Verification

**Files:**
- Modify only files changed by Spotless if formatting requires it.

- [ ] **Step 1: Apply formatting**

Run: `./gradlew spotlessApply`

Expected: command succeeds. Inspect any formatting changes before committing.

- [ ] **Step 2: Run full checks**

Run: `./gradlew check`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Inspect final git diff**

Run: `rtk git status --short`

Expected: only dry-run implementation, tests, docs, and this plan/spec are changed.

Run: `rtk git diff -- docs/superpowers/specs/2026-07-08-dry-run-mode-design.md docs/superpowers/plans/2026-07-08-dry-run-mode.md verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/CliPreflightChecker.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunPlanner.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunRenderer.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunArtifactWriter.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunPlannerTest.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunArtifactWriterTest.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt docs/architecture.md`

Expected: diff contains no unrelated changes and no secrets.

- [ ] **Step 4: Commit final formatting or verification fixes**

If `spotlessApply` changed files or verification required small fixes, commit them:

```bash
git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli verity/cli/src/test/kotlin/me/chrisbanes/verity/cli docs/architecture.md docs/superpowers/specs/2026-07-08-dry-run-mode-design.md docs/superpowers/plans/2026-07-08-dry-run-mode.md
git commit -m "chore: finalize dry-run mode"
```

If there are no new changes since the previous task commits, skip this commit.
