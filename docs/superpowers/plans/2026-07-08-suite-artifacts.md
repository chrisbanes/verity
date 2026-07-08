# Suite Artifacts And CI Results Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist durable `verity run` artifacts, generated flows, structured journey results, suite summaries, and CI-friendly exit codes.

**Architecture:** Put stable serializable result contracts in `:verity:core`, collect segment-level metadata in `:verity:agent`, and keep filesystem layout plus suite writing in `:verity:cli`. `RunCommand` creates one timestamped run directory per invocation and passes a journey-scoped artifact recorder into `Orchestrator` so the agent can save evidence at the point it is produced without owning the output root.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization JSON, Clikt, coroutines, assertk, kotlinx-coroutines-test, Gradle via `rtk ./gradlew`.

---

## File Structure

- Create `verity/core/src/main/kotlin/me/chrisbanes/verity/core/result/RunResultContract.kt`: serializable DTOs and stable lowercase enums for suite summaries, journey results, segment results, evidence references, and errors.
- Create `verity/core/src/test/kotlin/me/chrisbanes/verity/core/result/RunResultContractTest.kt`: JSON contract tests for field names and enum wire values.
- Modify `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/OrchestratorResult.kt`: add execution metadata fields to `SegmentResult` and `JourneyResult`.
- Create `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/JourneyArtifactRecorder.kt`: small interface used by `Orchestrator` to persist generated flows and evidence.
- Modify `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt`: accept the recorder, write slow-path YAML before execution, save visual screenshots and tree hierarchy evidence, and populate segment metadata.
- Modify `verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt`: prove metadata and recorder calls for fast path, slow path, loop fallback, tree assertions, and visual assertions.
- Create `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunArtifacts.kt`: run directory naming, slugging, journey artifact contexts, JSON writing, and `JourneyArtifactRecorder` implementation.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`: create run artifacts, write per-journey JSON and `summary.json`, pass recorders into orchestrators, continue suite execution after journey failures, and map exit codes.
- Modify `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt`: assert artifact files, relative references, and exit codes using injected runner/clock.
- Modify `docs/architecture.md`: document artifact layout, result contract, module ownership, and exit-code table.

## Task 1: Core Result Contract

**Files:**
- Create: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/result/RunResultContract.kt`
- Create: `verity/core/src/test/kotlin/me/chrisbanes/verity/core/result/RunResultContractTest.kt`

- [ ] **Step 1: Write failing serialization tests**

Create `verity/core/src/test/kotlin/me/chrisbanes/verity/core/result/RunResultContractTest.kt`:

```kotlin
package me.chrisbanes.verity.core.result

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.serialization.json.Json
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Platform

class RunResultContractTest {
  private val json = Json {
    prettyPrint = false
    encodeDefaults = true
    explicitNulls = false
  }

  @Test
  fun `journey result serializes stable lowercase values and camelCase fields`() {
    val result = JourneyArtifactResult(
      journey = JourneyArtifactIdentity(
        name = "Login",
        file = "journeys/login.journey.yaml",
        app = "com.example.app",
        platform = Platform.ANDROID_TV,
      ),
      passed = false,
      failedAt = 2,
      segments = listOf(
        SegmentArtifactResult(
          index = 2,
          passed = false,
          executionMode = SegmentExecutionMode.SLOW,
          actions = listOf("Navigate to Settings"),
          assertion = AssertionArtifact(description = "Settings is visible", mode = AssertMode.TREE),
          reasoning = "Expected Settings but saw Home",
          generatedFlows = listOf("flows/001-login/segment-002-actions.yaml"),
          evidence = listOf(
            EvidenceArtifact(type = EvidenceType.HIERARCHY, path = "evidence/001-login/segment-002-tree.txt"),
          ),
          error = ArtifactError(kind = ArtifactErrorKind.JOURNEY_FAILURE, message = "Expected Settings but saw Home"),
        ),
      ),
    )

    val encoded = json.encodeToString(JourneyArtifactResult.serializer(), result)

    assertThat(encoded).contains("\"platform\":\"android-tv\"")
    assertThat(encoded).contains("\"executionMode\":\"slow\"")
    assertThat(encoded).contains("\"mode\":\"tree\"")
    assertThat(encoded).contains("\"type\":\"hierarchy\"")
    assertThat(encoded).contains("\"kind\":\"journey_failure\"")
    assertThat(encoded).contains("\"generatedFlows\"")
  }

  @Test
  fun `suite summary serializes status counts and journey references`() {
    val summary = SuiteArtifactSummary(
      formatVersion = 1,
      timestamp = "2026-07-08T14:35:12Z",
      inputPath = "journeys",
      status = ArtifactStatus.FAILED,
      total = 2,
      passed = 1,
      failed = 1,
      journeys = listOf(
        SuiteJourneyArtifact(path = "journeys/001-login.json", name = "Login", status = ArtifactStatus.PASSED),
        SuiteJourneyArtifact(path = "journeys/002-browse.json", name = "Browse", status = ArtifactStatus.FAILED),
      ),
      error = ArtifactError(kind = ArtifactErrorKind.JOURNEY_FAILURE, message = "One or more journeys failed"),
    )

    val encoded = json.encodeToString(SuiteArtifactSummary.serializer(), summary)

    assertThat(encoded).contains("\"formatVersion\":1")
    assertThat(encoded).contains("\"status\":\"failed\"")
    assertThat(encoded).contains("\"passed\":1")
    assertThat(encoded).contains("\"failed\":1")
    assertThat(encoded).contains("\"path\":\"journeys/001-login.json\"")
  }

  @Test
  fun `platform and assert mode decode from stable wire values`() {
    val decoded = json.decodeFromString(
      JourneyArtifactResult.serializer(),
      """
      {
        "journey":{"name":"Login","file":"login.journey.yaml","app":"com.example.app","platform":"android"},
        "passed":true,
        "segments":[{"index":0,"passed":true,"executionMode":"assertion-only","assertion":{"description":"Home","mode":"visible"}}]
      }
      """.trimIndent(),
    )

    assertThat(decoded.journey.platform).isEqualTo(Platform.ANDROID_MOBILE)
    assertThat(decoded.segments.single().assertion?.mode).isEqualTo(AssertMode.VISIBLE)
    assertThat(decoded.segments.single().executionMode).isEqualTo(SegmentExecutionMode.ASSERTION_ONLY)
  }
}
```

- [ ] **Step 2: Run the core result contract tests and verify failure**

Run:

```bash
rtk ./gradlew :verity:core:test --tests "me.chrisbanes.verity.core.result.RunResultContractTest"
```

Expected: compile failure because `me.chrisbanes.verity.core.result` types do not exist.

- [ ] **Step 3: Add serializable result DTOs**

Create `verity/core/src/main/kotlin/me/chrisbanes/verity/core/result/RunResultContract.kt`:

```kotlin
package me.chrisbanes.verity.core.result

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Platform

@Serializable
data class JourneyArtifactIdentity(
  val name: String,
  val file: String,
  val app: String,
  @Serializable(with = PlatformWireSerializer::class) val platform: Platform,
)

@Serializable
data class AssertionArtifact(
  val description: String,
  @Serializable(with = AssertModeWireSerializer::class) val mode: AssertMode,
)

@Serializable
data class EvidenceArtifact(
  val type: EvidenceType,
  val path: String,
)

@Serializable
data class ArtifactError(
  val kind: ArtifactErrorKind,
  val message: String,
)

@Serializable
data class SegmentArtifactResult(
  val index: Int,
  val passed: Boolean,
  val executionMode: SegmentExecutionMode,
  val actions: List<String> = emptyList(),
  val assertion: AssertionArtifact? = null,
  val reasoning: String = "",
  val generatedFlows: List<String> = emptyList(),
  val evidence: List<EvidenceArtifact> = emptyList(),
  val error: ArtifactError? = null,
)

@Serializable
data class JourneyArtifactResult(
  val journey: JourneyArtifactIdentity,
  val passed: Boolean,
  val failedAt: Int? = null,
  val segments: List<SegmentArtifactResult> = emptyList(),
)

@Serializable
data class SuiteJourneyArtifact(
  val path: String,
  val name: String,
  val status: ArtifactStatus,
)

@Serializable
data class SuiteArtifactSummary(
  val formatVersion: Int,
  val timestamp: String,
  val inputPath: String,
  val status: ArtifactStatus,
  val total: Int,
  val passed: Int,
  val failed: Int,
  val journeys: List<SuiteJourneyArtifact> = emptyList(),
  val error: ArtifactError? = null,
  val platform: String? = null,
  val provider: String? = null,
  val navigatorModel: String? = null,
  val inspectorModel: String? = null,
)

@Serializable
enum class ArtifactStatus {
  @SerialName("passed") PASSED,
  @SerialName("failed") FAILED,
}

@Serializable
enum class SegmentExecutionMode {
  @SerialName("fast") FAST,
  @SerialName("slow") SLOW,
  @SerialName("loop") LOOP,
  @SerialName("assertion-only") ASSERTION_ONLY,
}

@Serializable
enum class EvidenceType {
  @SerialName("flow") FLOW,
  @SerialName("screenshot") SCREENSHOT,
  @SerialName("hierarchy") HIERARCHY,
}

@Serializable
enum class ArtifactErrorKind {
  @SerialName("parser_failure") PARSER_FAILURE,
  @SerialName("setup_failure") SETUP_FAILURE,
  @SerialName("journey_failure") JOURNEY_FAILURE,
}

object PlatformWireSerializer : KSerializer<Platform> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Platform", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Platform) = encoder.encodeString(
    when (value) {
      Platform.ANDROID_TV -> "android-tv"
      Platform.ANDROID_MOBILE -> "android"
      Platform.IOS -> "ios"
    },
  )

  override fun deserialize(decoder: Decoder): Platform = when (val value = decoder.decodeString()) {
    "android-tv" -> Platform.ANDROID_TV
    "android" -> Platform.ANDROID_MOBILE
    "ios" -> Platform.IOS
    else -> error("Unknown platform: $value")
  }
}

object AssertModeWireSerializer : KSerializer<AssertMode> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AssertMode", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: AssertMode) = encoder.encodeString(value.name.lowercase())

  override fun deserialize(decoder: Decoder): AssertMode = when (val value = decoder.decodeString()) {
    "visible" -> AssertMode.VISIBLE
    "focused" -> AssertMode.FOCUSED
    "tree" -> AssertMode.TREE
    "visual" -> AssertMode.VISUAL
    else -> error("Unknown assert mode: $value")
  }
}
```

- [ ] **Step 4: Run core result contract tests and verify pass**

Run:

```bash
rtk ./gradlew :verity:core:test --tests "me.chrisbanes.verity.core.result.RunResultContractTest"
```

Expected: PASS.

- [ ] **Step 5: Commit core result contract**

Run:

```bash
rtk git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/result/RunResultContract.kt verity/core/src/test/kotlin/me/chrisbanes/verity/core/result/RunResultContractTest.kt
rtk git commit -m "Add run artifact result contract"
```

Expected: commit succeeds.

## Task 2: Agent Artifact Metadata And Recorder Interface

**Files:**
- Create: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/JourneyArtifactRecorder.kt`
- Modify: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/OrchestratorResult.kt`
- Modify: `verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt`

- [ ] **Step 1: Write failing agent metadata tests**

Append these tests to `verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt` before the companion object:

```kotlin
  @Test
  fun `fast path segment records execution mode and source actions`() = runTest {
    val session = FakeDeviceSession()
    val recorder = RecordingArtifactRecorder()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { FakeTextAgent { error("unused") } } },
      inspectorFactory = { InspectorAgent(treeAgentFactory = { FakeTextAgent { error("unused") } }, evaluateVisualContent = { _, _, _ -> error("unused") }) },
      artifactRecorder = recorder,
    )

    val result = orchestrator.run(
      Journey(
        name = "fast",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Action("scroll down")),
      ),
    )

    assertThat(result.segments.single().executionMode).isEqualTo(SegmentExecutionMode.FAST)
    assertThat(result.segments.single().actions).containsExactly("scroll down")
    assertThat(result.segments.single().generatedFlows).isEmpty()
  }

  @Test
  fun `slow path segment records generated flow reference before execution`() = runTest {
    val session = FakeDeviceSession()
    val recorder = RecordingArtifactRecorder()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { FakeTextAgent { "appId: $APP_ID\n---\n- tapOn: \"Settings\"" } } },
      inspectorFactory = { InspectorAgent(treeAgentFactory = { FakeTextAgent { error("unused") } }, evaluateVisualContent = { _, _, _ -> error("unused") }) },
      artifactRecorder = recorder,
    )

    val result = orchestrator.run(
      Journey(
        name = "slow",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Action("navigate to settings page")),
      ),
    )

    assertThat(result.segments.single().executionMode).isEqualTo(SegmentExecutionMode.SLOW)
    assertThat(result.segments.single().generatedFlows).containsExactly("flows/segment-000-actions.yaml")
    assertThat(recorder.generatedFlows).containsExactly("0:actions:appId: $APP_ID\n---\n- tapOn: \"Settings\"")
  }

  @Test
  fun `tree assertion records hierarchy evidence`() = runTest {
    val session = FakeDeviceSession()
    val recorder = RecordingArtifactRecorder()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { FakeTextAgent { error("unused") } } },
      inspectorFactory = { InspectorAgent(treeAgentFactory = { FakeTextAgent { "{\"passed\":true,\"reasoning\":\"Tree ok\"}" } }, evaluateVisualContent = { _, _, _ -> error("unused") }) },
      artifactRecorder = recorder,
    )

    val result = orchestrator.run(
      Journey(
        name = "tree",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Assert("Home", AssertMode.TREE)),
      ),
    )

    assertThat(result.segments.single().evidence.map { it.path }).containsExactly("evidence/segment-000-tree.txt")
    assertThat(recorder.hierarchies.single()).contains("text=Home")
  }

  @Test
  fun `visual assertion records screenshot evidence`() = runTest {
    val session = FakeDeviceSession()
    val recorder = RecordingArtifactRecorder()
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = { NavigatorAgent("unused") { FakeTextAgent { error("unused") } } },
      inspectorFactory = { InspectorAgent(treeAgentFactory = { FakeTextAgent { error("unused") } }, evaluateVisualContent = { _, _, path -> "{\"passed\":true,\"reasoning\":\"Visual ok at $path\"}" }) },
      artifactRecorder = recorder,
    )

    val result = orchestrator.run(
      Journey(
        name = "visual",
        app = APP_ID,
        platform = Platform.ANDROID_MOBILE,
        steps = listOf(JourneyStep.Assert("Logo visible", AssertMode.VISUAL)),
      ),
    )

    assertThat(result.segments.single().evidence.map { it.path }).containsExactly("evidence/segment-000-visual.png")
    assertThat(recorder.screenshotRequests).containsExactly(0)
  }
```

Add this nested recorder class near the existing fake session:

```kotlin
  private class RecordingArtifactRecorder : JourneyArtifactRecorder {
    val generatedFlows = mutableListOf<String>()
    val hierarchies = mutableListOf<String>()
    val screenshotRequests = mutableListOf<Int>()

    override suspend fun saveGeneratedFlow(segmentIndex: Int, label: String, yaml: String): String {
      generatedFlows += "$segmentIndex:$label:$yaml"
      return "flows/segment-${segmentIndex.toString().padStart(3, '0')}-$label.yaml"
    }

    override suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String {
      hierarchies += hierarchy
      return "evidence/segment-${segmentIndex.toString().padStart(3, '0')}-tree.txt"
    }

    override suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact {
      screenshotRequests += segmentIndex
      return JourneyScreenshotArtifact(
        path = java.nio.file.Files.createTempFile("verity-visual-test", ".png"),
        relativePath = "evidence/segment-${segmentIndex.toString().padStart(3, '0')}-visual.png",
      )
    }
  }
```

Add imports if missing:

```kotlin
import assertk.assertions.contains
import me.chrisbanes.verity.core.result.SegmentExecutionMode
```

- [ ] **Step 2: Run agent tests and verify failure**

Run:

```bash
rtk ./gradlew :verity:agent:test --tests "me.chrisbanes.verity.agent.OrchestratorTest"
```

Expected: compile failure because `JourneyArtifactRecorder`, `JourneyScreenshotArtifact`, and new segment fields do not exist.

- [ ] **Step 3: Add recorder interface**

Create `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/JourneyArtifactRecorder.kt`:

```kotlin
package me.chrisbanes.verity.agent

import java.nio.file.Path

interface JourneyArtifactRecorder {
  suspend fun saveGeneratedFlow(segmentIndex: Int, label: String, yaml: String): String? = null

  suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String? = null

  suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact? = null
}

data class JourneyScreenshotArtifact(
  val path: Path,
  val relativePath: String,
)

object NoOpJourneyArtifactRecorder : JourneyArtifactRecorder
```

- [ ] **Step 4: Add segment metadata fields**

Modify `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/OrchestratorResult.kt`:

```kotlin
package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.result.ArtifactError
import me.chrisbanes.verity.core.result.EvidenceArtifact
import me.chrisbanes.verity.core.result.SegmentExecutionMode

data class SegmentResult(
  val index: Int,
  val passed: Boolean,
  val assertionMode: AssertMode? = null,
  val assertionDescription: String? = null,
  val reasoning: String = "",
  val executionMode: SegmentExecutionMode = SegmentExecutionMode.ASSERTION_ONLY,
  val actions: List<String> = emptyList(),
  val generatedFlows: List<String> = emptyList(),
  val evidence: List<EvidenceArtifact> = emptyList(),
  val error: ArtifactError? = null,
)

data class JourneyResult(
  val journeyName: String,
  val segments: List<SegmentResult>,
) {
  val passed: Boolean get() = segments.all { it.passed }
  val failedAt: Int? get() = segments.firstOrNull { !it.passed }?.index
}

data class LoopResult(
  val satisfied: Boolean,
  val iterations: Int,
  val reasoning: String = "",
  val generatedFlows: List<String> = emptyList(),
)
```

- [ ] **Step 5: Leave metadata changes uncommitted for Task 3**

Run:

```bash
rtk git status --short
```

Expected: the new recorder interface, updated result types, and failing tests are present in the worktree. Do not commit yet because Task 3 completes the implementation that makes these tests pass.

## Task 3: Orchestrator Artifact Capture

**Files:**
- Modify: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt`
- Modify: `verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt`

- [ ] **Step 1: Update `Orchestrator` constructor and slow-path capture**

Modify the constructor and slow-path helper in `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt`:

```kotlin
class Orchestrator(
  private val session: DeviceSession,
  private val navigatorFactory: () -> NavigatorAgent,
  private val inspectorFactory: () -> InspectorAgent,
  private val context: String = "",
  private val artifactRecorder: JourneyArtifactRecorder = NoOpJourneyArtifactRecorder,
) {
```

Add this private result type and helper near `executeSlowPath`:

```kotlin
  private data class ExecutedFlow(
    val result: FlowResult,
    val reference: String?,
  )

  private suspend fun executeSlowPath(
    instructions: List<String>,
    appId: String,
    platform: Platform,
    navigator: NavigatorAgent,
    segmentIndex: Int,
    label: String,
  ): ExecutedFlow {
    val yaml = navigator.generate(instructions, appId, platform, context)
    val reference = artifactRecorder.saveGeneratedFlow(segmentIndex, label, yaml)
    return ExecutedFlow(result = session.executeFlow(yaml), reference = reference)
  }
```

Replace existing calls to `executeSlowPath(...)` with the new signature and use `flow.reference` when building `SegmentResult`.

- [ ] **Step 2: Populate action segment metadata**

In `executeSegment`, replace the action execution block with this logic:

```kotlin
    var executionMode = SegmentExecutionMode.ASSERTION_ONLY
    val generatedFlows = mutableListOf<String>()

    if (segment.actions.isNotEmpty()) {
      val instructions = segment.actions.map { it.instruction }
      if (isFastPath(instructions, platform)) {
        executionMode = SegmentExecutionMode.FAST
        executeFastPath(instructions, appId, platform, navigator)
      } else {
        executionMode = SegmentExecutionMode.SLOW
        val flow = executeSlowPath(
          instructions = instructions,
          appId = appId,
          platform = platform,
          navigator = navigator,
          segmentIndex = segment.index,
          label = "actions",
        )
        flow.reference?.let(generatedFlows::add)
        if (!flow.result.success) {
          return SegmentResult(
            index = segment.index,
            passed = false,
            reasoning = "Flow execution failed: ${flow.result.output}",
            executionMode = executionMode,
            actions = instructions,
            generatedFlows = generatedFlows,
            error = ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, "Flow execution failed: ${flow.result.output}"),
          )
        }
      }
    }
```

Add imports:

```kotlin
import me.chrisbanes.verity.core.result.ArtifactError
import me.chrisbanes.verity.core.result.ArtifactErrorKind
import me.chrisbanes.verity.core.result.EvidenceArtifact
import me.chrisbanes.verity.core.result.EvidenceType
import me.chrisbanes.verity.core.result.SegmentExecutionMode
```

- [ ] **Step 3: Capture loop generated flow references**

Change `executeLoop` so fallback slow-path iterations save `label = "loop-${actionsExecuted.toString().padStart(3, '0')}"` and append returned references to `LoopResult.generatedFlows`:

```kotlin
    val generatedFlows = mutableListOf<String>()

    repeat(max) {
      if (session.containsText(until)) {
        return LoopResult(true, actionsExecuted, "Text '$until' found after $actionsExecuted iterations", generatedFlows)
      }

      if (interaction != null) {
        executor.execute(interaction)
        actionsExecuted += 1
      } else {
        val flow = executeSlowPath(
          instructions = listOf(action),
          appId = appId,
          platform = platform,
          navigator = navigator,
          segmentIndex = segmentIndex,
          label = "loop-${actionsExecuted.toString().padStart(3, '0')}",
        )
        flow.reference?.let(generatedFlows::add)
        if (!flow.result.success) {
          return LoopResult(false, actionsExecuted, "Loop flow execution failed: ${flow.result.output}", generatedFlows)
        }
        actionsExecuted += 1
      }
    }
```

Pass `segment.index` into `executeLoop` and build loop `SegmentResult` with `executionMode = SegmentExecutionMode.LOOP`, `actions = listOf(loop.action)`, and `generatedFlows = loopResult.generatedFlows`.

- [ ] **Step 4: Capture tree and visual evidence**

Change tree assertion evaluation to save the hierarchy string:

```kotlin
    AssertMode.TREE -> {
      val hierarchy = session.captureHierarchy(HierarchyFilter.CONTENT)
      val reference = artifactRecorder.saveHierarchy(segmentIndex, hierarchy)
      val verdict = inspector.evaluateTree(hierarchy, description)
      AssertionEvaluation(
        verdict = verdict,
        evidence = listOfNotNull(reference?.let { EvidenceArtifact(EvidenceType.HIERARCHY, it) }),
      )
    }
```

Change visual assertion evaluation to ask the recorder for a persistent screenshot path and fall back to the existing temp-file cleanup when the recorder returns null:

```kotlin
    AssertMode.VISUAL -> {
      val artifact = artifactRecorder.screenshotPath(segmentIndex)
      val screenshotPath = artifact?.path ?: withContext(Dispatchers.IO) { Files.createTempFile("verity-screenshot-", ".png") }
      try {
        session.captureScreenshot(screenshotPath)
        val verdict = inspector.evaluateVisual(screenshotPath, description)
        AssertionEvaluation(
          verdict = verdict,
          evidence = listOfNotNull(artifact?.relativePath?.let { EvidenceArtifact(EvidenceType.SCREENSHOT, it) }),
        )
      } finally {
        if (artifact == null) {
          withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(screenshotPath) }
        }
      }
    }
```

Use a small private data class:

```kotlin
  private data class AssertionEvaluation(
    val verdict: InspectionVerdict,
    val evidence: List<EvidenceArtifact> = emptyList(),
  )
```

Then build assertion `SegmentResult` with `assertionMode = assert.mode`, `assertionDescription = assert.description`, `reasoning`, `evidence`, `actions = segment.actions.map { it.instruction }`, and `executionMode` from earlier action handling.

- [ ] **Step 5: Run agent tests and verify pass**

Run:

```bash
rtk ./gradlew :verity:agent:test --tests "me.chrisbanes.verity.agent.OrchestratorTest"
```

Expected: PASS.

- [ ] **Step 6: Commit orchestrator artifact capture**

Run:

```bash
rtk git add verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/JourneyArtifactRecorder.kt verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/OrchestratorResult.kt verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt
rtk git commit -m "Capture journey segment artifacts"
```

Expected: commit succeeds.

## Task 4: CLI Artifact Writer

**Files:**
- Create: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunArtifacts.kt`
- Add tests in: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt`

- [ ] **Step 1: Write failing artifact writer tests**

Append this test to `RunCommandTest`:

```kotlin
  @Test
  fun `run artifacts create stable directories and relative references`() = kotlinx.coroutines.test.runTest {
    val dir = createTempDirectory("verity-artifacts").toFile()
    try {
      val writer = RunArtifactWriter(
        outputRoot = dir,
        clock = java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T14:35:12Z"), java.time.ZoneOffset.UTC),
      )
      val run = writer.createRun(suiteSlugSource = "My Suite")
      val journey = run.journey(index = 1, name = "Login")

      val flow = journey.saveGeneratedFlow(segmentIndex = 2, label = "actions", yaml = "appId: com.example\n---\n- tapOn: Settings")
      val tree = journey.saveHierarchy(segmentIndex = 2, hierarchy = "[text=Home]")
      val screenshot = journey.screenshotPath(segmentIndex = 3)

      assertThat(run.directory.toFile().name).isEqualTo("20260708-143512-my-suite")
      assertThat(flow).isEqualTo("flows/001-login/segment-002-actions.yaml")
      assertThat(tree).isEqualTo("evidence/001-login/segment-002-tree.txt")
      assertThat(screenshot.relativePath).isEqualTo("evidence/001-login/segment-003-visual.png")
      assertThat(File(run.directory.toFile(), flow).readText()).contains("tapOn: Settings")
      assertThat(File(run.directory.toFile(), tree).readText()).isEqualTo("[text=Home]")
    } finally {
      dir.deleteRecursively()
    }
  }
```

Add imports:

```kotlin
import assertk.assertions.isEqualTo
```

- [ ] **Step 2: Run the new CLI test and verify failure**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests "me.chrisbanes.verity.cli.RunCommandTest.run artifacts create stable directories and relative references"
```

Expected: compile failure because `RunArtifactWriter` does not exist.

- [ ] **Step 3: Implement artifact writer and journey recorder**

Create `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunArtifacts.kt`:

```kotlin
package me.chrisbanes.verity.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.chrisbanes.verity.agent.JourneyArtifactRecorder
import me.chrisbanes.verity.agent.JourneyScreenshotArtifact
import me.chrisbanes.verity.core.result.JourneyArtifactResult
import me.chrisbanes.verity.core.result.SuiteArtifactSummary

class RunArtifactWriter(
  private val outputRoot: File,
  private val clock: Clock = Clock.systemUTC(),
) {
  private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
  }

  suspend fun createRun(suiteSlugSource: String): RunArtifactDirectory = withContext(Dispatchers.IO) {
    val timestamp = DATE_FORMAT.format(clock.instant().atZone(ZoneOffset.UTC))
    val directory = outputRoot.toPath().resolve("runs").resolve("$timestamp-${slugArtifactName(suiteSlugSource, "run")}")
    Files.createDirectories(directory)
    RunArtifactDirectory(directory = directory, json = json)
  }

  private companion object {
    val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
  }
}

class RunArtifactDirectory(
  val directory: Path,
  private val json: Json,
) {
  fun journey(index: Int, name: String): JourneyRunArtifactRecorder {
    val key = "${index.toString().padStart(3, '0')}-${slugArtifactName(name, "journey")}"
    return JourneyRunArtifactRecorder(directory = directory, key = key)
  }

  suspend fun writeJourneyResult(path: String, result: JourneyArtifactResult) = withContext(Dispatchers.IO) {
    val target = directory.resolve(path)
    Files.createDirectories(target.parent)
    Files.writeString(target, json.encodeToString(JourneyArtifactResult.serializer(), result))
  }

  suspend fun writeSummary(summary: SuiteArtifactSummary) = withContext(Dispatchers.IO) {
    Files.writeString(directory.resolve("summary.json"), json.encodeToString(SuiteArtifactSummary.serializer(), summary))
  }
}

internal fun slugArtifactName(value: String, fallback: String): String {
  val slug = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
  return slug.ifEmpty { fallback }
}

class JourneyRunArtifactRecorder(
  private val directory: Path,
  private val key: String,
) : JourneyArtifactRecorder {
  override suspend fun saveGeneratedFlow(segmentIndex: Int, label: String, yaml: String): String = withContext(Dispatchers.IO) {
    val relative = "flows/$key/segment-${segmentIndex.toString().padStart(3, '0')}-$label.yaml"
    val target = directory.resolve(relative)
    Files.createDirectories(target.parent)
    Files.writeString(target, yaml)
    relative
  }

  override suspend fun saveHierarchy(segmentIndex: Int, hierarchy: String): String = withContext(Dispatchers.IO) {
    val relative = "evidence/$key/segment-${segmentIndex.toString().padStart(3, '0')}-tree.txt"
    val target = directory.resolve(relative)
    Files.createDirectories(target.parent)
    Files.writeString(target, hierarchy)
    relative
  }

  override suspend fun screenshotPath(segmentIndex: Int): JourneyScreenshotArtifact = withContext(Dispatchers.IO) {
    val relative = "evidence/$key/segment-${segmentIndex.toString().padStart(3, '0')}-visual.png"
    val target = directory.resolve(relative)
    Files.createDirectories(target.parent)
    JourneyScreenshotArtifact(path = target, relativePath = relative)
  }
}
```

- [ ] **Step 4: Run artifact writer test and verify pass**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests "me.chrisbanes.verity.cli.RunCommandTest.run artifacts create stable directories and relative references"
```

Expected: PASS.

- [ ] **Step 5: Commit CLI artifact writer**

Run:

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunArtifacts.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt
rtk git commit -m "Add CLI run artifact writer"
```

Expected: commit succeeds.

## Task 5: RunCommand Summary Writing And Exit Codes

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt`

- [ ] **Step 1: Write failing RunCommand artifact and exit-code tests**

Change the helper at the bottom of `RunCommandTest` to accept a clock, then add these tests:

```kotlin
  private fun runCommand(
    clock: java.time.Clock = java.time.Clock.systemUTC(),
    runner: suspend (List<ResolvedJourney>) -> SuiteRunResult,
  ): RunCommand = RunCommand(suiteRunner = runner, clock = clock)
```

```kotlin
  @Test
  fun `successful run writes journey result and suite summary`() {
    val dir = createTempDirectory("verity-run-artifacts-success").toFile()
    try {
      val file = writeJourney(dir, "single.journey.yaml", "Single journey")
      val output = File(dir, "out")
      val command = runCommand(
        clock = java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T14:35:12Z"), java.time.ZoneOffset.UTC),
      ) { journeys ->
        SuiteRunResult(
          journeys.map { resolved ->
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(resolved.journey.name, listOf(SegmentResult(index = 0, passed = true))),
            )
          },
        )
      }

      val result = Verity().subcommands(command).test("--output-path ${output.absolutePath} run ${file.absolutePath}")

      assertThat(result.statusCode).isEqualTo(0)
      val runDir = output.resolve("runs/20260708-143512-single-journey")
      assertThat(runDir.resolve("summary.json").isFile).isTrue()
      assertThat(runDir.resolve("journeys/001-single-journey.json").isFile).isTrue()
      assertThat(runDir.resolve("summary.json").readText()).contains("\"status\": \"passed\"")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `journey failure exits four and still writes failed summary`() {
    val dir = createTempDirectory("verity-run-artifacts-fail").toFile()
    try {
      val file = writeJourney(dir, "failure.journey.yaml", "Failure journey")
      val output = File(dir, "out")
      val command = runCommand(
        clock = java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T14:35:12Z"), java.time.ZoneOffset.UTC),
      ) { journeys ->
        SuiteRunResult(
          journeys.map { resolved ->
            ResolvedJourneyResult(
              resolvedJourney = resolved,
              result = JourneyResult(
                resolved.journey.name,
                listOf(SegmentResult(index = 0, passed = false, reasoning = "Expected Home")),
              ),
            )
          },
        )
      }

      val result = Verity().subcommands(command).test("--output-path ${output.absolutePath} run ${file.absolutePath}")

      assertThat(result.statusCode).isEqualTo(4)
      assertThat(output.resolve("runs/20260708-143512-failure-journey/summary.json").readText()).contains("\"status\": \"failed\"")
      assertThat(result.output).contains("Suite result: FAILED")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `parser input failure exits two and writes summary`() {
    val dir = createTempDirectory("verity-run-artifacts-parser").toFile()
    try {
      val output = File(dir, "out")
      val missing = File(dir, "missing.journey.yaml")

      val result = Verity()
        .subcommands(runCommand(clock = java.time.Clock.fixed(java.time.Instant.parse("2026-07-08T14:35:12Z"), java.time.ZoneOffset.UTC)) { error("unused") })
        .test("--output-path ${output.absolutePath} run ${missing.absolutePath}")

      assertThat(result.statusCode).isEqualTo(2)
      assertThat(output.resolve("runs/20260708-143512-missing-journey-yaml/summary.json").readText()).contains("\"kind\": \"parser_failure\"")
    } finally {
      dir.deleteRecursively()
    }
  }
```

- [ ] **Step 2: Run RunCommand tests and verify failure**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests "me.chrisbanes.verity.cli.RunCommandTest"
```

Expected: failures because `RunCommand` does not accept a clock and does not write artifacts or return exit code `4` for journey failures.

- [ ] **Step 3: Add clock injection and exit-code constants**

Modify the `RunCommand` constructor:

```kotlin
class RunCommand(
  private val loadJourney: (File, AssertionStrategy) -> Journey = JourneyLoader::fromFile,
  private val listJourneyFiles: (File) -> List<File> = JourneyLoader::listJourneyFiles,
  private val suiteRunner: (suspend (List<ResolvedJourney>) -> SuiteRunResult)? = null,
  private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) : CliktCommand(name = "run") {
```

Add constants near the data classes:

```kotlin
private const val EXIT_INPUT = 2
private const val EXIT_SETUP = 3
private const val EXIT_JOURNEY = 4
```

Use `CliktError(message, statusCode = EXIT_JOURNEY)` for journey failures and `CliktError(message, statusCode = EXIT_INPUT)` for input/parser failures.

- [ ] **Step 4: Convert `JourneyResult` into core artifact DTOs**

Add helpers to `RunCommand.kt`:

```kotlin
private fun ResolvedJourneyResult.toArtifactResult(filePath: String): JourneyArtifactResult = JourneyArtifactResult(
  journey = JourneyArtifactIdentity(
    name = resolvedJourney.journey.name,
    file = filePath,
    app = resolvedJourney.journey.app,
    platform = resolvedJourney.journey.platform,
  ),
  passed = result.passed,
  failedAt = result.failedAt,
  segments = result.segments.map { segment ->
    SegmentArtifactResult(
      index = segment.index,
      passed = segment.passed,
      executionMode = segment.executionMode,
      actions = segment.actions,
      assertion = segment.assertionMode?.let {
        AssertionArtifact(description = segment.assertionDescription.orEmpty(), mode = it)
      },
      reasoning = segment.reasoning,
      generatedFlows = segment.generatedFlows,
      evidence = segment.evidence,
      error = segment.error,
    )
  },
)
```

- [ ] **Step 5: Create run directory before resolving journeys and write summaries**

In `RunCommand.run`, after resolving config and `path`, create the writer and run directory before `resolveJourneys`:

```kotlin
    val artifactWriter = RunArtifactWriter(resolved.outputPath, clock)
    val runArtifacts = artifactWriter.createRun(suiteSlugSource = path.nameWithoutExtension.ifEmpty { path.name })
```

Wrap input parsing in `try/catch` and write a failed summary before throwing `CliktError(statusCode = EXIT_INPUT)`:

```kotlin
    val journeys = try {
      resolveJourneys(path, resolved.assertionStrategy, resolved.platform)
    } catch (e: Exception) {
      runArtifacts.writeSummary(
        SuiteArtifactSummary(
          formatVersion = 1,
          timestamp = java.time.Instant.now(clock).toString(),
          inputPath = path.path,
          status = ArtifactStatus.FAILED,
          total = 0,
          passed = 0,
          failed = 0,
          error = ArtifactError(ArtifactErrorKind.PARSER_FAILURE, e.message ?: "Journey input parsing failed"),
        ),
      )
      throw CliktError(e.message ?: "Journey input parsing failed", statusCode = EXIT_INPUT)
    }
```

After running the suite, write each journey result and summary:

```kotlin
    val suiteJourneys = mutableListOf<SuiteJourneyArtifact>()
    suiteResult.results.forEachIndexed { index, item ->
      val journeyKey = "${(index + 1).toString().padStart(3, '0')}-${slugArtifactName(item.resolvedJourney.journey.name, "journey")}" 
      val resultPath = "journeys/$journeyKey.json"
      runArtifacts.writeJourneyResult(resultPath, item.toArtifactResult(item.resolvedJourney.file.displayPath()))
      suiteJourneys += SuiteJourneyArtifact(
        path = resultPath,
        name = item.resolvedJourney.journey.name,
        status = if (item.result.passed) ArtifactStatus.PASSED else ArtifactStatus.FAILED,
      )
    }
    runArtifacts.writeSummary(
      SuiteArtifactSummary(
        formatVersion = 1,
        timestamp = java.time.Instant.now(clock).toString(),
        inputPath = path.path,
        status = if (suiteResult.passed) ArtifactStatus.PASSED else ArtifactStatus.FAILED,
        total = suiteResult.results.size,
        passed = suiteResult.passedCount,
        failed = suiteResult.failedCount,
        journeys = suiteJourneys,
        error = if (suiteResult.passed) null else ArtifactError(ArtifactErrorKind.JOURNEY_FAILURE, "One or more journeys failed"),
      ),
    )
```

Use the `slugArtifactName` function from `RunArtifacts.kt` for the journey result path so `journeys/`, `flows/`, and `evidence/` keys match exactly.

- [ ] **Step 6: Pass journey-specific recorders into real orchestrators**

Change `runSuiteWithDevice` to accept `runArtifacts: RunArtifactDirectory`, then create the orchestrator inside `journeys.mapIndexed` so each journey receives its recorder:

```kotlin
return SuiteRunResult(
  journeys.mapIndexed { index, resolved ->
    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = navigatorFactory,
      inspectorFactory = inspectorFactory,
      context = projectContext.text,
      artifactRecorder = runArtifacts.journey(index + 1, resolved.journey.name),
    )
    ResolvedJourneyResult(
      resolvedJourney = resolved,
      result = orchestrator.run(resolved.journey),
    )
  },
)
```

Before this return block, extract the existing inline `navigatorFactory = { ... }` and `inspectorFactory = { ... }` lambdas into local variables named exactly `navigatorFactory` and `inspectorFactory` so the snippet above compiles without changing their bodies.

- [ ] **Step 7: Run RunCommand tests and update old exit-code expectations**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests "me.chrisbanes.verity.cli.RunCommandTest"
```

Expected: tests pass after updating existing failing journey assertions from status code `1` to `4`, and input/parser assertions from `1` to `2` where applicable.

- [ ] **Step 8: Commit RunCommand artifact integration**

Run:

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunArtifacts.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt
rtk git commit -m "Write run artifact summaries"
```

Expected: commit succeeds.

## Task 6: Setup Failure Summary And Architecture Docs

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandSmokeTest.kt`
- Modify: `docs/architecture.md`

- [ ] **Step 1: Write failing setup failure summary test**

Add to `RunCommandSmokeTest`:

```kotlin
  @Test
  fun `setup failure writes summary and exits three`() {
    val journeyUrl = javaClass.classLoader.getResource("smoke/minimal.journey.yaml")!!
    val output = kotlin.io.path.createTempDirectory("verity-setup-summary").toFile()
    try {
      val result = Verity()
        .subcommands(RunCommand(), ListCommand(), McpCommand())
        .test(
          listOf(
            "--provider", "ollama",
            "--context-path", "/nonexistent/context",
            "--require-context",
            "--output-path", output.absolutePath,
            "run",
            java.io.File(journeyUrl.toURI()).absolutePath,
          ),
        )

      assertThat(result.statusCode).isEqualTo(3)
      val summary = output.resolve("runs").listFiles()!!.single().resolve("summary.json").readText()
      assertThat(summary).contains("\"kind\": \"setup_failure\"")
      assertThat(summary).contains("Required project context directory")
    } finally {
      output.deleteRecursively()
    }
  }
```

- [ ] **Step 2: Run smoke test and verify failure**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests "me.chrisbanes.verity.cli.RunCommandSmokeTest.setup failure writes summary and exits three"
```

Expected: failure because setup errors still exit `1` and may not write summaries.

- [ ] **Step 3: Write setup failure summaries in `RunCommand`**

Wrap `runSuiteWithDevice` invocation in `RunCommand.run`:

```kotlin
    val suiteResult = try {
      suiteRunner?.invoke(journeys)
        ?: runSuiteWithDevice(parent, config, resolved, path, journeys, runArtifacts)
    } catch (e: CancellationException) {
      throw e
    } catch (e: CliktError) {
      runArtifacts.writeSummary(
        SuiteArtifactSummary(
          formatVersion = 1,
          timestamp = java.time.Instant.now(clock).toString(),
          inputPath = path.path,
          status = ArtifactStatus.FAILED,
          total = journeys.size,
          passed = 0,
          failed = journeys.size,
          error = ArtifactError(ArtifactErrorKind.SETUP_FAILURE, e.message ?: "Setup failed"),
        ),
      )
      throw CliktError(e.message ?: "Setup failed", statusCode = EXIT_SETUP)
    }
```

Import `kotlin.coroutines.cancellation.CancellationException`.

- [ ] **Step 4: Update `docs/architecture.md`**

Add a new section after project configuration or CLI mode:

```markdown
## Run Artifacts And CI Results

`verity run` always writes a timestamped run directory below the resolved `paths.output` / `--output-path` root. The default root is `build/verity`.

```text
build/verity/
  runs/
    20260708-143512-my-suite/
      summary.json
      journeys/
        001-login.json
      flows/
        001-login/segment-002-actions.yaml
      evidence/
        001-login/segment-003-visual.png
        001-login/segment-004-tree.txt
```

`summary.json` contains the output format version, timestamp, input path, overall status, pass/fail counts, journey result references, and top-level parser/setup/journey error details when present. Each journey result contains journey identity, app, platform, pass/fail state, first failed segment, segment execution mode, source actions, assertion mode, reasoning, generated flow references, evidence references, and structured segment errors.

Module ownership:

- `:verity:core` owns serializable result DTOs and stable JSON wire values.
- `:verity:agent` owns segment metadata and calls a recorder when generated flows or assertion evidence are produced.
- `:verity:cli` owns run directory layout, JSON writing, summary aggregation, and exit-code mapping.

Exit codes:

| Code | Meaning |
|------|---------|
| `0` | All journeys passed. |
| `2` | Input or parser failure. |
| `3` | Setup, preflight, context, device, provider, model, credential, or artifact setup failure. |
| `4` | One or more journeys ran and failed. |
```

- [ ] **Step 5: Run setup failure smoke test and docs-independent CLI tests**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests "me.chrisbanes.verity.cli.RunCommandSmokeTest" --tests "me.chrisbanes.verity.cli.RunCommandTest"
```

Expected: PASS.

- [ ] **Step 6: Commit setup failure and docs**

Run:

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandSmokeTest.kt docs/architecture.md
rtk git commit -m "Document and handle run artifact failures"
```

Expected: commit succeeds.

## Task 7: Full Verification And Cleanup

**Files:**
- Modify only files touched by earlier tasks to fix formatting, imports, or failing checks.

- [ ] **Step 1: Run Spotless apply**

Run:

```bash
rtk ./gradlew spotlessApply
```

Expected: task succeeds and formats Kotlin/docs as configured.

- [ ] **Step 2: Run full checks**

Run:

```bash
rtk ./gradlew check
```

Expected: PASS. If it fails, fix only the failing issue, rerun the narrow failing task, then rerun `rtk ./gradlew check`.

- [ ] **Step 3: Inspect final diff and status**

Run:

```bash
rtk git status --short
rtk git diff --stat
rtk git diff
```

Expected: only intentional changes remain. Do not revert unrelated user changes.

- [ ] **Step 4: Commit verification fixes if needed**

If Spotless or check fixes changed files, run:

```bash
rtk git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/result/RunResultContract.kt verity/core/src/test/kotlin/me/chrisbanes/verity/core/result/RunResultContractTest.kt verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/JourneyArtifactRecorder.kt verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/OrchestratorResult.kt verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunArtifacts.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandSmokeTest.kt docs/architecture.md
rtk git commit -m "Polish suite artifact implementation"
```

Expected: commit succeeds. Skip this step if there are no uncommitted changes.

## Self-Review Notes

- Spec coverage: Tasks cover core result DTOs, timestamped run directories, per-journey JSON, suite summary JSON, generated-flow persistence, fast-path metadata, screenshot/tree evidence, exit codes, tests, and architecture docs.
- Scope: This is one cohesive CLI run artifact feature across existing module boundaries. MCP behavior is explicitly excluded.
- Type consistency: Plan uses `JourneyArtifactRecorder`, `JourneyScreenshotArtifact`, `SegmentExecutionMode`, `EvidenceArtifact`, and `ArtifactError` consistently across core, agent, and CLI tasks.
