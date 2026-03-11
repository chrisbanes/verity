# Phase 3: `:verity:agent` — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the LLM agent layer with flow generation, assertion evaluation, and the main orchestration engine.

**Architecture:** Two isolated agents (**Navigator** and **Inspector**) and a segment-aware **Orchestrator**. The Navigator generates Maestro YAML using a tiered model strategy (suggesting Claude 4.5 Haiku, Gemini 3.0 Flash, or GPT-5 mini). The Inspector evaluates assertions using capable models (Claude Sonnet 4.6, Gemini 3.1 Pro, or GPT 5.4). The Orchestrator employs a **subagent pattern**, executing each journey segment in an isolated session to minimize context window usage and prevent context drift.

**Tech Stack:** Koog (`ai.koog:koog-agents`, `ai.koog:prompt-executor-anthropic-client`), kotlinx.coroutines

**Design doc:** `docs/plans/2026-03-11-verity-design.md`

**Prerequisite:** Phase 0, Phase 1, and Phase 2 complete.

**Important:** The Koog API for agent construction, prompt DSL, and image attachment should be verified against the Koog documentation at https://docs.koog.ai/.

**Delivery mode (required):**
- **Scaffold milestone:** Prompt construction, parsing, and orchestration logic may land with temporary `TODO()` in live LLM execution points.
- **Production-ready milestone:** No `TODO()` remains in `:verity:agent` production sources. Koog calls are wired and exercised with a real API key in smoke validation.

### Task 0: Koog API validation spike (must happen first)

**Goal:** eliminate API uncertainty before implementing agents.

**Steps:**
1. Validate Koog agent construction APIs for model selection and executor wiring.
2. Validate multimodal attachment pattern for screenshot-based evaluation.
3. Confirm response extraction semantics (raw text vs structured object) for JSON parsing.
4. Update snippets in Tasks 2–4 to match verified APIs before coding.

---

### Task 1: Models configuration

**Files:**
- Create: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Models.kt`

**Step 1: Write the models config**

```kotlin
package me.chrisbanes.verity.agent

/**
 * LLM model configuration.
 *
 * Suggested NAVIGATOR models (Cheap/Structured):
 * - Anthropic: "claude-haiku-4-5" (Recommended)
 * - Google: "gemini-3.0-flash"
 * - OpenAI: "gpt-5-mini"
 *
 * Suggested INSPECTOR models (Capable/Vision):
 * - Anthropic: "claude-sonnet-4-6" (Recommended)
 * - Google: "gemini-3.1-pro"
 * - OpenAI: "gpt-5.4"
 */
object Models {
    const val NAVIGATOR = "claude-haiku-4-5"
    const val INSPECTOR = "claude-sonnet-4-6"
}
```

**Step 2: Commit**

```bash
git add verity/agent/src/
git commit -m "feat(agent): add Models configuration"
```

---

### Task 2: NavigatorAgent

Converts natural language action steps into Maestro YAML.

**Files:**
- Create: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/NavigatorAgent.kt`
- Test: `verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/NavigatorAgentTest.kt`

**Step 1: Write the test**

Test the prompt construction (not the LLM call itself — that requires an API key).

```kotlin
package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import me.chrisbanes.verity.core.model.Platform
import kotlin.test.Test

class NavigatorAgentTest {
    @Test
    fun `system prompt includes bundled Maestro basics`() {
        val prompt = NavigatorAgent.buildSystemPrompt(
            platform = Platform.ANDROID_TV,
            bundledContext = "Maestro basics: appId header, waitForAnimationToEnd, extendedWaitUntil",
            injectedContext = "",
        )
        assertThat(prompt).contains("Maestro basics")
    }

    @Test
    fun `system prompt includes platform context for Android TV`() {
        val prompt = NavigatorAgent.buildSystemPrompt(
            platform = Platform.ANDROID_TV,
            bundledContext = "Bundled context",
            injectedContext = "",
        )
        assertThat(prompt).contains("Android TV")
        assertThat(prompt).contains("D-pad")
    }

    @Test
    fun `system prompt includes platform context for iOS`() {
        val prompt = NavigatorAgent.buildSystemPrompt(
            platform = Platform.IOS,
            bundledContext = "Bundled context",
            injectedContext = "",
        )
        assertThat(prompt).contains("iOS")
    }

    @Test
    fun `system prompt appends injected context`() {
        val injectedContext = "App uses custom navigation component"
        val prompt = NavigatorAgent.buildSystemPrompt(
            platform = Platform.ANDROID_TV,
            bundledContext = "Bundled context",
            injectedContext = injectedContext,
        )
        assertThat(prompt).contains("custom navigation component")
    }

    @Test
    fun `user message formats actions as numbered list`() {
        val actions = listOf("Launch the app", "Press D-pad down", "Press select")
        val message = NavigatorAgent.buildUserMessage(actions, "com.example.app")
        assertThat(message).contains("1. Launch the app")
        assertThat(message).contains("2. Press D-pad down")
        assertThat(message).contains("3. Press select")
        assertThat(message).contains("com.example.app")
    }

    @Test
    fun `strips markdown code fences from response`() {
        val response = "```yaml\nappId: com.example\n---\n- pressKey: back\n```"
        val cleaned = NavigatorAgent.cleanResponse(response)
        assertThat(cleaned).doesNotContain("```")
        assertThat(cleaned).contains("appId: com.example")
    }

    @Test
    fun `passes through clean response unchanged`() {
        val response = "appId: com.example\n---\n- pressKey: back"
        assertThat(NavigatorAgent.cleanResponse(response)).contains("appId: com.example")
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :verity:agent:test --tests "me.chrisbanes.verity.agent.NavigatorAgentTest"`
Expected: FAIL

**Step 3: Write the implementation**

```kotlin
package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.model.Platform

/**
 * Generates Maestro YAML flows from natural language action steps.
 * Uses a cheap model (Haiku-class) since the task is structured generation.
 */
class NavigatorAgent(
    private val agent: AIAgent,
    private val bundledContext: String,
) {

    /**
     * Generate Maestro YAML for the given actions.
     *
     * @param actions Natural language action instructions
     * @param appId The app package/bundle ID
     * @param platform Target platform
     * @param injectedContext Optional app-specific context from --context-path or MCP get_context
     * @return Generated Maestro YAML string
     */
    suspend fun generate(
        actions: List<String>,
        appId: String,
        platform: Platform,
        injectedContext: String = "",
    ): String {
        val systemPrompt = buildSystemPrompt(platform, bundledContext, injectedContext)
        val userMessage = buildUserMessage(actions, appId)

        // Koog agent call using injected dependency:
        // - If Koog supports per-call system prompt override, set it here.
        // - Otherwise inject an AIAgentFactory and create an agent with systemPrompt.
        // val response = agent.run(userMessage)
        // return cleanResponse(response)
        TODO("Wire Koog agent call — verify AIAgent API against Koog docs")
    }

    companion object {
        private val CODE_FENCE = Regex("```\\w*\\n?|```")

        fun buildSystemPrompt(
            platform: Platform,
            bundledContext: String,
            injectedContext: String,
        ): String {
            val platformInstructions = when (platform) {
                Platform.ANDROID_TV -> """
                    You are generating Maestro YAML for an Android TV app.
                    Android TV uses D-pad navigation (Remote Dpad Up/Down/Left/Right/Center).
                    Always add waitForAnimationToEnd after navigation actions.
                    Use extendedWaitUntil for content that needs time to load.
                """.trimIndent()

                Platform.ANDROID_MOBILE -> """
                    You are generating Maestro YAML for an Android mobile app.
                    Use tap, swipe, scroll, and input commands.
                    Always add waitForAnimationToEnd after navigation actions.
                """.trimIndent()

                Platform.IOS -> """
                    You are generating Maestro YAML for an iOS app.
                    Use tap, swipe, scroll, and input commands.
                    Always add waitForAnimationToEnd after navigation actions.
                """.trimIndent()
            }

            return buildString {
                appendLine("Generate ONLY valid Maestro YAML. No explanation, no markdown code blocks, just raw YAML.")
                appendLine("Start with `appId: <id>`, then `---`, then commands.")
                appendLine("Do NOT include screenshots or assertions.")
                appendLine()
                appendLine("Bundled context (always present):")
                appendLine(bundledContext)
                appendLine()
                appendLine(platformInstructions)
                if (injectedContext.isNotBlank()) {
                    appendLine()
                    appendLine("Injected app-specific context (optional):")
                    appendLine(injectedContext)
                }
            }.trim()
        }

        fun buildUserMessage(actions: List<String>, appId: String): String = buildString {
            appendLine("App ID: $appId")
            appendLine()
            appendLine("Generate a Maestro YAML flow for these actions:")
            actions.forEachIndexed { index, action ->
                appendLine("${index + 1}. $action")
            }
        }.trim()

        fun cleanResponse(response: String): String =
            response.replace(CODE_FENCE, "").trim()
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :verity:agent:test --tests "me.chrisbanes.verity.agent.NavigatorAgentTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add verity/agent/src/
git commit -m "feat(agent): add NavigatorAgent with platform-aware prompts"
```

---

### Task 3: InspectorAgent

Evaluates assertions against screen state using tree text or screenshots.

**Files:**
- Create: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/InspectorAgent.kt`
- Test: `verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/InspectorAgentTest.kt`

**Step 1: Write the test**

```kotlin
package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import assertk.assertions.isFalse
import me.chrisbanes.verity.core.model.InspectionVerdict
import kotlin.test.Test

class InspectorAgentTest {
    @Test
    fun `system prompt describes inspector role`() {
        val prompt = InspectorAgent.SYSTEM_PROMPT
        assertThat(prompt).contains("testing inspector")
        assertThat(prompt).contains("JSON")
    }

    @Test
    fun `tree user message includes hierarchy and assertion`() {
        val message = InspectorAgent.buildTreeMessage("hierarchy text", "Home is visible")
        assertThat(message).contains("hierarchy text")
        assertThat(message).contains("Home is visible")
    }

    @Test
    fun `visual user message includes assertion`() {
        val message = InspectorAgent.buildVisualMessage("Backdrop image loads")
        assertThat(message).contains("Backdrop image loads")
    }

    @Test
    fun `parses valid JSON verdict`() {
        val json = """{"passed": true, "reasoning": "Home text found"}"""
        val verdict = InspectorAgent.parseVerdict(json)
        assertThat(verdict.passed).isTrue()
        assertThat(verdict.reasoning).contains("Home text found")
    }

    @Test
    fun `parses JSON with code fences`() {
        val json = "```json\n{\"passed\": false, \"reasoning\": \"not found\"}\n```"
        val verdict = InspectorAgent.parseVerdict(json)
        assertThat(verdict.passed).isFalse()
    }

    @Test
    fun `parse failure returns failed verdict`() {
        val verdict = InspectorAgent.parseVerdict("garbage response")
        assertThat(verdict.passed).isFalse()
        assertThat(verdict.reasoning).contains("parse error")
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :verity:agent:test --tests "me.chrisbanes.verity.agent.InspectorAgentTest"`
Expected: FAIL

**Step 3: Write the implementation**

```kotlin
package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.model.InspectionVerdict
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Evaluates assertions against screen state.
 * Uses a capable model (Sonnet-class) for accuracy.
 * Supports both tree-based (text) and visual (screenshot) evaluation.
 */
class InspectorAgent(
    private val treeAgent: AIAgent,
    private val visualAgent: AIAgent,
) {

    /**
     * Evaluate an assertion against the accessibility tree text.
     */
    suspend fun evaluateTree(hierarchy: String, assertion: String): InspectionVerdict {
        val message = buildTreeMessage(hierarchy, assertion)
        // val response = treeAgent.run(message)
        // return parseVerdict(response)
        TODO("Wire Koog agent call for tree evaluation")
    }

    /**
     * Evaluate an assertion against a screenshot.
     */
    suspend fun evaluateVisual(screenshotFile: File, assertion: String): InspectionVerdict {
        val message = buildVisualMessage(assertion)
        // Use Koog image DSL: visualAgent.run { image(screenshotFile); text(message) }
        // return parseVerdict(response)
        TODO("Wire Koog agent call with vision for visual evaluation")
    }

    companion object {
        private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
        private val CODE_FENCE = Regex("```\\w*\\n?|```")

        const val SYSTEM_PROMPT = """You are a visual testing inspector for a mobile/TV app.
Evaluate whether a screenshot or accessibility tree matches an assertion.
Respond with ONLY JSON: {"passed": true/false, "reasoning": "..."}
Do not include any other text or explanation outside the JSON."""

        fun buildTreeMessage(hierarchy: String, assertion: String): String = buildString {
            appendLine("Accessibility tree:")
            appendLine(hierarchy)
            appendLine()
            appendLine("Assertion to evaluate: $assertion")
        }.trim()

        fun buildVisualMessage(assertion: String): String =
            "Evaluate the attached screenshot against this assertion: $assertion"

        fun parseVerdict(response: String): InspectionVerdict {
            val cleaned = response.replace(CODE_FENCE, "").trim()
            return try {
                lenientJson.decodeFromString(InspectionVerdict.serializer(), cleaned)
            } catch (e: Exception) {
                InspectionVerdict(
                    passed = false,
                    reasoning = "Inspector parse error: ${e.message}. Raw response: $cleaned",
                )
            }
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :verity:agent:test --tests "me.chrisbanes.verity.agent.InspectorAgentTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add verity/agent/src/
git commit -m "feat(agent): add InspectorAgent with tree and visual evaluation"
```

---

### Task 4: Orchestrator

The main execution engine that runs journeys segment by segment.

**Files:**
- Create: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt`
- Create: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/OrchestratorResult.kt`
- Test: `verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt`

**Step 1: Write the result types**

```kotlin
package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.model.AssertMode

data class SegmentResult(
    val index: Int,
    val passed: Boolean,
    val assertionMode: AssertMode? = null,
    val reasoning: String = "",
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
)
```

**Step 2: Write the test**

Test the orchestrator's decision logic with a mock DeviceSession.

```kotlin
package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import me.chrisbanes.verity.core.model.Platform
import kotlin.test.Test

class OrchestratorTest {
    @Test
    fun `classifies all-key-mapped actions as fast path`() {
        val actions = listOf("press d-pad down", "press d-pad down", "press select")
        val isFastPath = Orchestrator.isFastPath(actions, Platform.ANDROID_TV)
        assertThat(isFastPath).isTrue()
    }

    @Test
    fun `classifies non-mappable actions as slow path`() {
        val actions = listOf("press d-pad down", "navigate to settings page")
        val isFastPath = Orchestrator.isFastPath(actions, Platform.ANDROID_TV)
        assertThat(isFastPath).isEqualTo(false)
    }
}
```

**Step 3: Run test to verify it fails**

Run: `./gradlew :verity:agent:test --tests "me.chrisbanes.verity.agent.OrchestratorTest"`
Expected: FAIL

**Step 4: Write the implementation**

```kotlin
package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.hierarchy.HierarchyFilter
import me.chrisbanes.verity.core.keymap.PlatformKeyMapper
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneySegment
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.journey.JourneySegmenter
import me.chrisbanes.verity.device.DeviceSession
import java.io.File

/**
 * Runs journeys segment by segment using a subagent pattern.
 * Each segment is isolated from previous ones to keep context windows small
 * and prevent interference between unrelated steps.
 */
class Orchestrator(
    private val session: DeviceSession,
    private val navigatorFactory: () -> NavigatorAgent,
    private val inspectorFactory: () -> InspectorAgent,
    private val context: String = "",
) {
    suspend fun run(journey: Journey): JourneyResult {
        val segments = JourneySegmenter.segment(journey.steps)
        val results = mutableListOf<SegmentResult>()

        for (segment in segments) {
            // Create fresh subagents for this segment to ensure isolation
            val navigator = navigatorFactory()
            val inspector = inspectorFactory()

            val result = executeSegment(segment, journey.app, journey.platform, navigator, inspector)
            results.add(result)
            if (!result.passed) break
        }

        return JourneyResult(journeyName = journey.name, segments = results)
    }

    private suspend fun executeSegment(
        segment: JourneySegment,
        appId: String,
        platform: Platform,
        navigator: NavigatorAgent,
        inspector: InspectorAgent,
    ): SegmentResult {
        // Execute actions
        if (segment.actions.isNotEmpty()) {
            val instructions = segment.actions.map { it.instruction }
            if (isFastPath(instructions, platform)) {
                executeFastPath(instructions, platform)
            } else {
                executeSlowPath(instructions, appId, platform, navigator)
            }
        }

        // Execute loop
        segment.loop?.let { loop ->
            val loopResult = executeLoop(loop.action, loop.until, loop.max, platform)
            return SegmentResult(
                index = segment.index,
                passed = loopResult.satisfied,
                reasoning = loopResult.reasoning,
            )
        }

        // Evaluate assertion
        segment.assertion?.let { assert ->
            val verdict = evaluateAssertion(assert.description, assert.mode, inspector)
            return SegmentResult(
                index = segment.index,
                passed = verdict,
                assertionMode = assert.mode,
                reasoning = "",
            )
        }

        // Actions only, no assertion — always passes
        return SegmentResult(index = segment.index, passed = true)
    }

    private suspend fun executeFastPath(instructions: List<String>, platform: Platform) {
        val mapper = PlatformKeyMapper.forPlatform(platform)
        for (instruction in instructions) {
            val keyName = mapper.map(instruction)!!
            session.pressKey(keyName)
            session.waitForAnimationToEnd()
        }
    }

    private suspend fun executeSlowPath(
        instructions: List<String>,
        appId: String,
        platform: Platform,
        navigator: NavigatorAgent,
    ) {
        val yaml = navigator.generate(instructions, appId, platform, context)
        session.executeFlow(yaml)
    }

    private suspend fun executeLoop(
        action: String,
        until: String,
        max: Int,
        platform: Platform,
    ): LoopResult {
        val mapper = PlatformKeyMapper.forPlatform(platform)
        val keyName = mapper.map(action)

        for (i in 1..max) {
            // Check exit condition (deterministic first)
            if (session.containsText(until)) {
                return LoopResult(satisfied = true, iterations = i, reasoning = "Text '$until' found")
            }

            // Execute action
            if (keyName != null) {
                session.pressKey(keyName)
                session.waitForAnimationToEnd()
            } else {
                TODO("LLM fallback for non-key-mapped loop actions")
            }
        }

        // Final check after max iterations
        if (session.containsText(until)) {
            return LoopResult(satisfied = true, iterations = max, reasoning = "Text '$until' found after max iterations")
        }

        return LoopResult(satisfied = false, iterations = max, reasoning = "Text '$until' not found after $max iterations")
    }

    private suspend fun evaluateAssertion(
        description: String,
        mode: AssertMode,
        inspector: InspectorAgent,
    ): Boolean {
        return when (mode) {
            AssertMode.VISIBLE -> session.containsText(description)
            AssertMode.FOCUSED -> session.checkFocused(description)
            AssertMode.TREE -> {
                val hierarchy = session.captureHierarchy(HierarchyFilter.CONTENT)
                inspector.evaluateTree(hierarchy, description).passed
            }
            AssertMode.VISUAL -> {
                val tempFile = File.createTempFile("verity-screenshot-", ".png")
                try {
                    session.captureScreenshot(tempFile)
                    inspector.evaluateVisual(tempFile, description).passed
                } finally {
                    tempFile.delete()
                }
            }
        }
    }

    companion object {
        fun isFastPath(instructions: List<String>, platform: Platform): Boolean {
            val mapper = PlatformKeyMapper.forPlatform(platform)
            return mapper.allMappable(instructions)
        }
    }
}
```

**Step 5: Run tests to verify they pass**

Run: `./gradlew :verity:agent:test --tests "me.chrisbanes.verity.agent.OrchestratorTest"`
Expected: PASS

**Step 6: Commit**

```bash
git add verity/agent/src/
git commit -m "feat(agent): add Orchestrator with fast-path key mapping and cost-aware assertions"
```

---

### Task 5: Run all agent tests

**Step 1: Run the full agent test suite**

```bash
./gradlew :verity:agent:test
```

Expected: ALL PASS

---

## Verification

After all tasks, `:verity:agent` should contain:

```
verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/
├── Models.kt
├── NavigatorAgent.kt
├── InspectorAgent.kt
├── Orchestrator.kt
└── OrchestratorResult.kt
```

For the **scaffold milestone**, temporary stubs at Koog call-sites are acceptable while API wiring is being validated.

For the **production-ready milestone** (required before relying on autonomous execution), all Koog call-sites must be fully wired and no `TODO()` may remain in production code.

**Definition of Done (production-ready):**
- No `TODO()` in `verity/agent/src/main/kotlin/**`.
- `./gradlew :verity:agent:test` passes.
- Smoke run proves one tree assertion and one visual assertion path execute with live model calls.
