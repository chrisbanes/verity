# Verity Architecture

Verity is a Kotlin/JVM tool that combines device automation (Maestro SDK) with LLM reasoning (via Koog) to run end-to-end journey tests on Android TV, Android mobile, and iOS devices. It operates in two modes:

1. **CLI mode** (`verity run`) — Executes journey YAML files against a connected device, using LLMs to generate structured actions and evaluate assertions. `verity run --dry-run` parses, segments, and renders mapped or generated actions as YAML without device access.
2. **MCP server mode** (`verity mcp`) — Exposes device control as MCP tools so an AI agent can interactively drive the device.

LLMs serve two purposes: action generation (turning English into a validated ordered action list) and assertion evaluation (judging whether screen state matches an expectation). Deterministic interactions and assertions bypass those calls when possible.

Use the [domain glossary](../CONTEXT.md) for terminology and the [documentation index](README.md) for behavior specs and ADRs. This file describes module ownership and execution; the specs hold detailed command and result contracts.

---

## Module Dependency Graph

```
:verity:core  <── :verity:device  <── :verity:agent  <── :verity:cli
                        ^                                      |
                   :verity:mcp  <──────────────────────────────┘
```

| Module | Depends on | Purpose |
|--------|-----------|---------|
| `:verity:core` | nothing (kotlinx.serialization, Kaml) | Models, journey format, step parsing, focus-condition grammar, segmenter, interaction mapper, hierarchy renderer, assertion mode inferrer |
| `:verity:device` | `:verity:core` | `DeviceSession` interface and platform-specific implementations (Android via Dadb + Maestro gRPC, iOS via Maestro XCTest HTTP) |
| `:verity:agent` | `:verity:core`, `:verity:device` | Koog LLM setup, NavigatorAgent, InspectorAgent, ConditionEvaluator, ConditionWaiter, Orchestrator |
| `:verity:mcp` | `:verity:core`, `:verity:device` | MCP server (stdio + HTTP), 14 tools, session manager, snapshot store |
| `:verity:cli` | `:verity:agent`, `:verity:mcp` | Clikt commands: `run`, `list`, `mcp` |
| `:verity:smoke-tests` | `:verity:cli` | Offline archive/protocol fixtures and explicit Android/iOS device smoke tests |

**Key rule:** `:verity:mcp` does not depend on `:verity:agent`. The MCP server exposes raw device capabilities — the external AI agent provides the intelligence.

---

## Gradle build and caching

`build-logic` provides the `verity.kotlin-jvm` and `verity.spotless` convention plugins. Each module applies its own conventions; build scripts do not configure other projects. Kotlin modules use JVM 21, JUnit Platform, assertk, and kotlinx-coroutines-test.

Build caching, configuration caching, and isolated projects are enabled in `gradle.properties`. Configuration-cache and isolation violations fail the build. Run `./gradlew check --no-scan` for validation; a second identical invocation can reuse the configuration cache.

The optional HTTP remote build cache follows Haze's setup. Set all three Gradle properties `remoteBuildCacheUrl`, `remoteBuildCacheUsername`, and `remoteBuildCachePassword` to enable it. Blank or missing values disable it. `remoteBuildCachePush` defaults to false. The local build cache remains enabled for local builds and for CI without a configured remote cache; CI disables the local build cache when the remote cache is enabled.

The three CLI fat JAR tasks merge service descriptors, Kotlin module metadata and Log4j plugin caches, and fail if duplicate ZIP entries remain. They share reproducible entry ordering, omitted entry timestamps and ZIP64 settings. All three archive tasks are excluded from build caching because the artifacts exceed the remote cache upload limit; compilation and test tasks remain cacheable. Unchanged local archive outputs can still be up to date.

CI and release workflows map the Actions secrets `GRADLE_REMOTE_CACHE_URL`, `GRADLE_REMOTE_CACHE_USERNAME`, and `GRADLE_REMOTE_CACHE_PASSWORD` to those properties. Same-repository pull requests and tagged releases read from the remote cache with `remoteBuildCachePush=false`; only pushes to `main` enable Gradle cache uploads. Fork pull requests do not receive Actions secrets and fall back to the local cache. When the remote cache is configured, CI excludes the disabled local build cache from the Actions cache. The workflows work before the secrets are added.

CI builds and verifies packaged inputs before native tests. The independent
`smoke-android` job uses an API 34 AOSP x86-64 emulator on Linux through
`reactivecircus/android-emulator-runner`. The `smoke-ios` job uses `macos-latest`
and `futureware-tech/simulator-action` to select an available iOS simulator,
erase it, wait for boot and shut it down after the job. No simulator model or
runtime version is pinned. The macOS pre-native build uses `--no-daemon`.

Gradle commands run directly in the workflow. Android tests use the emulator
action's `ANDROID_SERIAL`; iOS tests receive the simulator action's UUID through
`IOS_SIMULATOR_UDID`. Packaged and ordinary tagged smoke tests are explicitly
forced to execute; native tests are excluded from the default offline `check`.

Linux Android tests exercise the Linux and universal JARs; macOS iOS tests
exercise the macOS and universal JARs. Normal CI does not test macOS-specific
Android interoperability. There is no shared Android-to-iOS runner or handoff,
and no bootstrap observer or supplementary health diagnostics. The packaged
harness retains its own process cleanup, caller cancellation and functional test
reports.

`:verity:cli:hostJars` builds universal, macOS ARM64 and Linux x86-64 archives.
`verifyHostJars` checks their resource inventories and packaged ABI;
`verifyPackagedGrpc` checks the aligned Java gRPC graph. Both are dependencies of
CLI `check`. The smoke module's `packagedProbeJar` and `packagedGrpcProbe` provide
test-only factory and device-free ABI verification; `packagedAndroidTest` and
`packagedIosTest` exercise matching-host and universal production archives through
real devices, CLI runs and both MCP transports.

`:verity:cli:packageRelease` produces the three JAR assets and named SHA256
manifest. The release workflow serializes work per version tag without cancelling
an active publication. It reuses an existing release only when its exact four
assets match; otherwise it refuses to overwrite. New uploads are read back and
downloaded for tag, name, size and SHA256 verification before formula generation
and a tap update. The internal JSON manifest is not a fifth published asset.
This is future-tag wiring, not evidence that a release has been published. See
[host packaging](specs/host-packaging.md) for the user-facing policy and measured
payloads.

To opt out locally, pass `--no-build-cache`, `--no-isolated-projects`, or `--no-configuration-cache` as appropriate. Disable isolated projects as well when disabling configuration caching.

---

## Technology Stack

| Component | Library | Purpose |
|-----------|---------|---------|
| CLI | Clikt | Argument parsing, subcommands |
| YAML | Kaml | Journey deserialization with custom serializers |
| Serialization | kotlinx.serialization | JSON/YAML encoding/decoding |
| LLM | Koog (JetBrains), Codex app-server | API backends through Koog; isolated ChatGPT backend through Codex |
| Android device | Dadb | ADB over TCP — persistent shared connection |
| Android automation | Maestro SDK (embedded) | gRPC driver, UI automation, hierarchy capture |
| iOS automation | Maestro XCTest client | HTTP client to on-device XCTest server (port 22087) |
| MCP server | MCP Kotlin SDK | Tool registration, stdio/HTTP transport |
| HTTP server | Ktor (Netty) | HTTP transport for MCP |
| gRPC | grpc-netty-shaded | Bundled Netty to avoid version conflicts with Ktor |

### Netty Conflict Resolution

Maestro SDK uses gRPC with Netty 4.1; Ktor uses Netty 4.2. Resolved by:
- Excluding `grpc-netty` globally
- Using `grpc-netty-shaded` (bundles relocated Netty classes)
- Enforcing `io.grpc:grpc-bom:1.84.0` for the Java gRPC family; the separately versioned `grpc-kotlin-stub:1.5.0` is not assigned the Java BOM version

---

## Project Configuration

The CLI owns loading and resolving `verity/config.yaml`. Shared resolution applies CLI input first, project defaults second, and built-in defaults last; structured LLM settings preserve compatibility with legacy top-level keys. The resolved configuration supplies defaults to `run`, `list`, and the CLI command that starts MCP. Navigator and inspector reasoning-effort settings resolve independently from CLI flags, nested `llm` keys, legacy top-level keys, then unset. Their exact raw strings remain unchanged; normal run preflight validates explicit values for the selected provider, model and backend before device work.

See the [project-configuration spec](specs/project-configuration.md) for the schema, precedence, and assertion strategy, and the [preflight spec](specs/preflight-checks.md) for the supported effort/backend rows and rejection boundary. Runtime project-context loading is separate from the repository's domain glossary; its required/optional behavior is defined in the [project-context spec](specs/project-context.md).

---

## Journey Format

Journeys are YAML files describing user flows. File extension: `*.journey.yaml`.

```yaml
name: Browse home and open detail
app: com.example.launcher
platform: android-tv

steps:
  - Launch the app
  - "[?] Home"
  - Navigate down until TV Shows row
  - Press select
  - "[?] Detail page shows title"
  - "[?visual] Backdrop image loads"
  - "[?focused] Settings menu item"
  - "[?tree] Synopsis contains at least 2 sentences"
```

### Assertion Syntax

| Syntax | Meaning |
|--------|---------|
| `[?] text` | Assert with mode inferred by heuristics |
| `[?visible] text` | Deterministic substring match (free) |
| `[?focused] text` | Lenient focus detection (free) |
| `[?tree] text` | Accessibility tree + LLM evaluation |
| `[?visual] text` | Screenshot + LLM vision evaluation |

### Step Parsing Chain

Steps are parsed through a 6-stage priority chain:

1. **`[?mode]` prefix** — `[?visual]`, `[?tree]`, `[?visible]`, `[?focused]` locks the assertion mode
2. **`[?]` prefix** — Assert with mode chosen by `AssertModeInferrer`
3. **Wait inference** — anchored `Wait until <condition>`, with default 20 or positive whole-second limit
4. **Loop inference** — NL pattern: `<verb> ... until <condition>`
5. **Assertion inference** — NL keywords: "Verify...", "Ensure...", "Confirm...", "Check..."
6. **Default** — Action

### Assert Mode Inference

The `AssertModeInferrer` selects the cheapest sufficient mode:
- Contains visual keywords (color, colour, highlight, image, icon, animation, gradient, blur, backdrop, thumbnail, poster, artwork, badge, logo, overlay, opacity, shadow, border) → `VISUAL`
- 3 words or fewer, no visual keywords → `VISIBLE`
- Everything else → `TREE`

### Loop Inference

The `LoopStepInferrer` matches `<verb> ... until <condition>` with anchored `up to N times`, `max N`, `for up to N` or `N iterations` limits and an optional final period. The default limit is 20; zero still performs one immediate condition check.

The leading verb must be press, navigate, move, scroll, go or step. `Loop.actionInstructions` derives ordered, trimmed semicolon components and rejects empty components. Execution and dry-run consume that same representation; platform interaction mapping remains unchanged. Core's `FocusConditionParser` recognises the three anchored forms documented in [loop conditions](specs/loop-conditions.md).

---

## Data Model

```kotlin
data class Journey(
    val name: String,
    val app: String,
    val platform: Platform,
    val steps: List<JourneyStep>
)

sealed interface JourneyStep {
    data class Action(val instruction: String) : JourneyStep
    data class Assert(val description: String, val mode: AssertMode) : JourneyStep
    data class Loop(val action: String, val until: String, val max: Int = 20) : JourneyStep
    data class Wait(val until: String, val timeoutSeconds: Int = 20) : JourneyStep
}

enum class AssertMode { VISIBLE, FOCUSED, TREE, VISUAL }

enum class Platform { ANDROID_TV, ANDROID_MOBILE, IOS }

data class JourneySegment(
    val index: Int,
    val actions: List<JourneyStep.Action>,
    val assertion: JourneyStep.Assert? = null,
    val loop: JourneyStep.Loop? = null,
    val wait: JourneyStep.Wait? = null
)
```

### Segmentation

`JourneySegmenter` splits steps into independently executable segments:
- Actions accumulate until an assertion → segment with those actions + assertion
- Loops and waits flush pending actions as a separate segment, then become their own segment
- Trailing actions without an assertion become a final segment

Each segment is a checkpoint: execute its actions/assertion, loop or wait, then stop the journey on failure.

### Dry-Run Planning

Dry run is a CLI-owned planning path. `RunCommand` uses the normal journey resolver, then `DryRunPlanner` segments steps and classifies mapped interactions. Navigator creation is lazy: only slow-path actions or loops generate structured actions, which the preview renders as Maestro YAML. Assertions and waits are reported without evaluation.

Loop preview renders all mapped body interactions or one complete generated body, without checking the condition. Wait preview includes the condition and configured seconds without an inspector or navigator request. The planner has no device-session dependency and does not invoke `Orchestrator` or the inspector. Slow-path generation uses the navigator request and validation policy below. The entire suite is planned before Markdown writing: model failures exit `5`, while provider/context setup, local validation and required report-writing failures exit `3`, without a partial successful report or normal result JSON. See the [dry-run spec](specs/dry-run.md) for device boundaries, provider checks, Markdown output, and the current artifact side effects of shared input resolution.

---

## Device Layer

### Interface

```kotlin
interface DeviceSession : AutoCloseable {
    val platform: Platform

    suspend fun executeActions(flow: ActionFlow): FlowResult
    suspend fun executeFlow(yaml: String): FlowResult
    suspend fun pressKey(keyName: String)
    suspend fun pressKey(keyName: String, longPress: Boolean) // short forwards; long unsupported by default
    suspend fun pressKey(keycode: Int, longPress: Boolean)    // raw Android; unsupported by default
    suspend fun captureHierarchyTree(): HierarchyNode          // abstract
    suspend fun captureHierarchyTree(timeout: Duration): HierarchyNode // cooperative; default unsupported
    suspend fun captureScreenshot(output: Path)
    suspend fun captureScreenshot(output: Path, timeout: Duration)
    suspend fun shell(command: String): String
    suspend fun waitForAnimationToEnd()

    // Default methods (derived from captureHierarchyTree)
    suspend fun captureHierarchy(filter: HierarchyFilter = CONTENT): String
    suspend fun containsText(text: String, ignoreCase: Boolean = true): Boolean
    suspend fun checkFocused(text: String): Boolean

    // Animation control (no-op defaults, implemented by Android)
    suspend fun getAnimationState(): AnimationState? = null
    suspend fun disableAnimations()
    suspend fun restoreAnimationState(state: AnimationState)
}
```

Named short presses retain the legacy Maestro route. Android implements the new overloads with validated integer `input keyevent` commands, adding `--longpress` for holds; named holds use the current closed Maestro-to-Android key mapping. Raw codes must be non-negative JVM integers. iOS retains named short presses and explicitly rejects raw codes and holds. Animation-restoring session wrappers forward the overloads through the existing delegation.

### Structured execution

`ActionFlow` owns the app ID and complete ordered `Interaction` list. Core validates its schema and renders YAML for previews and saved artifacts. The device layer validates SDK keys and compiles the entire list to Maestro commands before any driver operation. Each list starts with app configuration; `LaunchApp` retains the caller's optional `clearState` value. No YAML file or parser participates in internal execution.

Android and iOS execute those commands through the existing Orchestra and driver connections. Runtime command failure returns the existing `FlowResult`; local preparation failures propagate separately, and caller cancellation remains cancellation. Supplied YAML continues through `executeFlow(String)` for MCP callers, using the canonical reader and temporary-file lifecycle. See [structured actions](specs/structured-actions.md).

### Implementations

**`AndroidDeviceSession`**: Connects via Dadb (ADB over TCP). Creates a Maestro instance with persistent gRPC connection. Supports device ID, IP:port, or auto-discovery.

**`IosDeviceSession`**: Installs XCTest runner on device/simulator. Communicates via HTTP to the on-device XCTest server (localhost:22087). Simulator management via `xcrun simctl`, physical devices via `devicectl`.

The factory shares an internal `IOSDevice` delegation adapter between Maestro's iOS driver and `IosDeviceSession`. Hierarchy calls forward the same hierarchy Boolean flag directly to the already-owned `XCTestIOSDevice`; other controller and close operations retain `LocalIOSDevice` delegation. This avoids Maestro 2.11.0's warning-only hierarchy scheduler, which its `LocalIOSDevice.close()` does not shut down.

Bounded capture includes complete acquisition, parsing, conversion and owned cleanup. Android preserves the interruptible SDK route with converter checkpoints. iOS owns a call to the recorded main runner endpoint, checked chunked body input and a fixed-schema Jackson-token decoder; no-argument capture stays SDK-backed. The helper never starts or closes the main runner. These cooperative routes do not guarantee forced termination of arbitrary SDK CPU work.

Bounded screenshot capture checks acquisition, chunked writing, publication and owned cleanup. It preserves prior output on failure and removes its staging files before return. Android retains Maestro’s public uncropped screenshot route; iOS streams the existing uncompressed XCTest endpoint under a request-owned cancel/join lifecycle. Both capture kinds expose `CaptureDeadlineExceededException` with a hierarchy/screenshot operation discriminator; bounded overloads require positive finite durations and unsupported sessions fail explicitly. No-argument capture contracts remain unchanged.

### Factory

```kotlin
object DeviceSessionFactory {
    suspend fun connect(
        platform: Platform,
        deviceId: String? = null,
        disableAnimations: Boolean = false
    ): DeviceSession
}
```

Auto-discovers the device if no ID is given. When `disableAnimations` is true, wraps the session in an `AnimationRestoringSession` decorator that saves scales on connect and restores on close.

### Preflight Checks

Core owns shared reports and filesystem checks, device owns platform readiness checks, and the CLI composes provider/model/credential checks with journey/context/device validation. MCP performs device and screenshot-target preflight, without LLM credential checks.

The CLI command that starts MCP still uses the shared configuration resolver and may reject invalid provider/model settings before server startup. This differs from MCP tool preflight and does not give the MCP module responsibility for LLM execution. See the [preflight spec](specs/preflight-checks.md) and [ADR-0001](adr/0001-mcp-device-boundary.md).

### Hierarchy Rendering

The accessibility tree renders to indented text with configurable attribute filtering:

```
[text=Home, resource-id=nav_home] (focused,clickable)
  [text=Movies]
  [text=TV Shows]
  [text=Settings]
```

| Filter | Allowed keys | Use case |
|--------|-------------|----------|
| `FOCUS` | text, resource-id, selected | Navigation assertions |
| `CONTENT` | text, accessibilityText, resource-id, bounds | Content verification (default) |
| `ALL` | everything (minus empty/defaults) | Debugging |

Empty strings, false booleans, and `enabled=true` are stripped. Empty container nodes with one or fewer children are collapsed.

### Focus Detection

Android TV (and sometimes iOS) places `focused=true` on container nodes while text lives in siblings or children. The lenient focus algorithm returns true if:
- A focused node contains the text
- A descendant of a focused node contains the text
- A sibling of a focused node contains the text
- An ancestor of a text node is focused

`FocusObservation` is a pure core representation of directly focused resource/path records plus internal whole-tree resource multiplicities. `observeFocus` visits the complete tree; `hasFocusChanged` uses resource-only identity when unique in both trees, otherwise resource+path or path alone. It compares sets, so unrelated text changes and a nonfocused duplicate added at another path do not alone change focus. Positional fallback is ambiguous across sibling edits. These observations are reusable by device and agent consumers without MCP dependencies.

Device `FocusChangeObserver.capture/awaitChange` supplies a separate baseline budget and serial monotonic post-action wait. It captures immediately, then waits 100 ms after each unchanged completed capture, capping every capture/delay by remaining time. Extraction and comparison use cancellation/deadline checkpoints. Last valid after-state is retained on timeout/error, and external cancellation propagates. See [focus-change waiting](specs/focus-change-waiting.md).

### Interaction Mapper

`InteractionMapper` maps instructions to typed interactions such as key presses, taps, scrolls, swipes, and long presses. Platform-specific implementations live in `:verity:core` without a device SDK dependency; `InteractionExecutor` in the agent layer executes the mapped interaction.

| Platform | Example mapping |
|----------|----------------|
| Android TV | "press d-pad down" → key press "Remote Dpad Down" |
| Android Mobile | "press back" → key press "back" |
| iOS | "press home" → key press "home" |

When all actions in a segment map to interactions, the orchestrator bypasses flow generation. Named-target interactions may still ask the navigator for a scroll direction when the target is not visible; a fast-path classification does not guarantee zero LLM calls during a real run.

---

## Agent Layer

### LLM Configuration

Verity uses a tiered model strategy via Koog. While Claude models are the recommended default for their strong reasoning and XML/YAML compliance, the system is provider-agnostic.

| Tier | Task | Suggested Models |
|------|------|------------------|
| **Navigator** (Cheap) | Structured action generation | `claude-haiku-4-5`, `gemini-3.0-flash`, `gpt-5-mini` |
| **Inspector** (Capable) | Assertion evaluation | `claude-sonnet-4-6`, `gemini-3.1-pro`, `gpt-5.4` |

Configured through Koog — can swap providers by updating the executor and model ID.

The Google provider defaults to `gemini-2.5-flash-lite` for navigation and `gemini-2.5-pro` for inspection. `gemini-3.1-pro-preview` is available as an explicit model selection.

Reasoning effort is an optional, independent string setting for each role. An explicit value is passed unchanged through that role's navigator or inspector requests using the selected backend's native field. An unset value leaves Koog's provider defaults in effect. Only the model/backend combinations listed in the [preflight spec](specs/preflight-checks.md) accept explicit settings; other rows continue to work with effort unset.

### NavigatorAgent

Converts natural language actions to an `ActionFlow` through a constructor-injected one-shot prompt callback returning Koog `Message.Assistant`. The CLI owns the executor and model selection for both execution and preview. The navigator receives the target `Platform` and adjusts output accordingly (D-pad commands for TV, tap/swipe for mobile, iOS gestures for iOS).

The system prompt requires a strict JSON object containing only an ordered `actions` array. The requested app ID supplies the flow context. Internal bundled guidance describes the supported action schema; app-specific context remains additive. Navigation uses `waitForAnimation`, and content readiness uses `waitUntilVisible`.

After completion metadata is checked, the navigator decodes the complete action list and validates it through the device-free command compiler. Unknown fields/types, malformed selectors, unsupported keys and invalid waits are model failures (exit `5`). Local command preparation or runner initialisation failures remain setup failures (exit `3`). Validation has no device or filesystem effects. Generated actions exclude scripts, nested flows, media, arbitrary configuration, coordinates, screenshots and assertions. Public supplied-YAML execution retains the canonical Maestro parser and its supported references.

Scroll suggestions accept only a trimmed, case-insensitive `UP`, `DOWN`, `LEFT`, `RIGHT` or `NONE`. A valid `NONE` keeps the ordinary navigation outcome. Explanations or unknown directions are invalid responses, and a failed scroll request stops before fallback interaction or later work.

### InspectorAgent

Evaluates assertions and semantic conditions using constructor-injected one-shot prompt callbacks returning Koog `Message.Assistant`, with no internal model selection:
- `evaluateTree(hierarchy, assertion, context)` evaluates the current hierarchy.
- `evaluateVisual(screenshotPath, assertion, context)` evaluates a current screenshot.

Both accept empty-by-default `InspectionContext` reference text and earlier images, labelled separately from current state. Normal runs produce no reference history. Completion metadata is retained until known truncation reasons are rejected, then strict JSON requires boolean `passed` and string `reasoning`; code fences, extra keys and empty reasoning are supported.

### Shared model request policy

Navigator flow generation and scroll suggestions, plus inspector tree/visual evaluation, share a request-owned 30-second timeout. Failed requests, that owned timeout, known case-insensitive truncation finish reasons (`length`, `max_tokens`, `incomplete`), blank replies and invalid response contracts raise `ModelFailureException`. Completion metadata is checked before decoding, even when truncated text looks valid. Valid negative inspector verdicts remain ordinary failed journey results.

Diagnostics contain fixed allowlisted stage/failure-class text. Raw replies, backend exception text, HTTP bodies/headers and raw causes are excluded; authored model diagnostics also redact API keys, bearer tokens and JWTs. Local validation errors remain separate from backend request failures. Caller cancellation and shorter enclosing deadlines propagate rather than becoming completed model failures. An overall wait deadline remains owned by its enclosing wait, while a request that fails before it follows this model-failure policy. The separate smart-planner fallback specified by [issue #59](https://github.com/chrisbanes/verity/issues/59) retains its deterministic recovery policy. MCP callers own their model policy under [ADR-0001](adr/0001-mcp-device-boundary.md).

### ConditionEvaluator

`evaluate(condition, context)` performs exactly one action-free check. A leading `visually` word is stripped and forces a current screenshot; other conditions use complete-condition literal matching, a recognised deterministic focus form, then CONTENT hierarchy inspection. False recognised focus never falls back to an inspector. Assertions and conditions share current-state capture, optional evidence persistence and temporary screenshot cleanup. The evaluator has no polling, delay or history accumulation, allowing callers to own broader deadlines.

### ConditionWaiter

`await(condition, timeout, context)` starts one monotonic budget, defaults to 20 seconds, and runs serial checks one second after each completed negative evaluation. Its bounded evaluator takes one remaining-budget hierarchy for literal/focus/tree work or a remaining-budget screenshot for visual inspection; tree traversal, rendering, persistence, model work and joined cleanup share that deadline. A true result is admitted only before expiry. Completed-check metadata/evidence is retained, with distinct production evidence paths per wait poll. Predeadline model failures survive slow cleanup; caller and foreign cancellation propagate, and overall expiry remains a wait timeout. Existing no-deadline assertion/loop evaluation is retained. See [wait conditions](specs/wait-conditions.md).

### Orchestrator

Runs journeys segment by segment using a **subagent pattern** to keep context windows small and focused. Each segment is treated as a discrete task for a fresh agent instance, preventing the accumulation of history from unrelated segments.

**Action execution (two paths):**
- Fast path: all actions map via `InteractionMapper`; the complete group is validated before `InteractionExecutor` performs its first action. Key presses keep the direct device path; other interactions use `executeActions()`, with scroll-to-find reasoning for off-screen named targets.
- Slow path: `NavigatorAgent` returns a validated `ActionFlow`; that same object is rendered for an optional YAML artifact, then passed to `executeActions()`.

**Assertion evaluation (four modes):**

| Mode | Method | Cost |
|------|--------|------|
| VISIBLE | `containsText()` — substring match | Free |
| FOCUSED | `checkFocused()` — lenient tree walk | Free |
| TREE | `InspectorAgent.evaluateTree()` | Medium |
| VISUAL | `InspectorAgent.evaluateVisual()` | High |

**Loop execution:** an immediate condition check precedes any body; one check follows every successful complete body, including the last permitted body. All mapped instructions execute in order. A body containing an unmapped instruction is generated once as a complete ordered flow. Only completed bodies count. Failed mapped/generated flows or automatic scrolls interrupt immediately without a new condition check or count; cancellation and navigator/inspector model failures propagate. Loop results retain condition, count, tier and reasoning separately from execution-error reasoning.

**Wait execution:** a wait dispatches before action/assertion fallthrough, performs no action or navigator generation, and returns mode `wait` with configured limit, actual elapsed milliseconds, completed-check count and optional last tier/reasoning/evidence. Timeout stops later segments as a failed journey result; fatal model/cancellation policy stays shared with other execution paths.

Each segment creates fresh navigator and inspector instances. Their reasoning is scoped to that segment rather than carrying an accumulated conversation through the journey.

---

## MCP Server

All three production fat JARs merge Log4j binary plugin metadata, retaining XML configuration factories, console appenders and layouts. The stdio CLI selects a dedicated Log4j console configuration before server logger initialization. SDK and server diagnostics retain their levels and text on stderr, while stdout carries MCP frames. HTTP and ordinary CLI commands retain their existing logging configuration.

### Transport

- **stdio** (default) — for Claude Code / IDE integration
- **HTTP** — Ktor/Netty on configurable host:port

### Session Manager

Thread-safe registry of persistent device connections:

```
sessions: Map<UUID, SessionEntry>
SessionEntry = { deviceId, DeviceSession, Mutex, lastUsedAt }
```

Per-session mutex for safe concurrent tool calls. Animation state is managed by the `AnimationRestoringSession` decorator in `DeviceSessionFactory`, not by the session manager.

### Snapshot Store

Capture-ordered map of full `HierarchyNode` trees, capped at ten per session. `capture_hierarchy` and `capture_focused_tree` store the full tree before filtering or bounding their text responses; reads preserve capture order and eviction removes the oldest capture. `resolvePair` validates explicit IDs and selects previous/latest defaults together under the store mutex, retaining both IDs/tree references before comparison.

`diff_hierarchy` compares root-relative child-index paths, every attribute/state and empty containers. Its compact JSON includes resolved IDs, full change/focus counts, samples and omission/truncation metadata. Focus summaries use `FocusDetector.isFocused`, the shared focused-state predicate. [The snapshot diff spec](specs/hierarchy-snapshot-diff.md) defines independent defaults, structured errors, structural identity limits and serialized 20-sample/500-character/16,000-character budgets.

Diff admission rechecks `isOpen` inside the acquired session callback. Removal before admission gives structured `session_unavailable`; admitted work finishes from retained trees while close waits for the session mutex. Only a missing-session lookup failure before callback entry is normalized; cancellation and unexpected callback failures preserve existing behavior. The snapshot lock is released before rendering, while the session lock remains held. A defaulted constructor-injected suspend `hierarchyDiffRenderer(UUID, ResolvedHierarchySnapshotPair)` delegates to the pure renderer and supplies a test barrier for this admission/close contract without changing the manager or tool schema.

### Screenshots

Both stdio and HTTP use the same `capture_screenshot` registration. Without `save_to_file`, capture a temporary PNG, encode as JPEG at 0.75 quality without resizing, return as base64, and delete the inline temporary files.

The optional string `save_to_file` saves a complete PNG. Relative paths resolve against the server process working directory; success returns `Screenshot saved to: <normalised absolute path>`. The parent must already exist and be writable; no directories are created. Every existing destination entry is refused, including directories and valid or dangling symlinks. A destination appearing during publication is also preserved. Callers choose another path or explicitly remove their own existing output before retrying.

`McpScreenshotFileSaver` captures into a unique sibling temporary file, validates the complete PNG, then publishes with an atomic hard link that never replaces an existing destination. This requires a filesystem supporting hard links. Unsupported or failed publication reports recovery using an existing writable directory on a supported local filesystem, without falling back to partial final-file writes.

Publication admission is the caller-cancellation check immediately before the blocking link operation. Cancellation observed before admission skips publication. Cancellation racing after admission, even before the link syscall, or after publication may leave a complete caller-owned PNG. The saver never deletes a published destination. It attempts deletion of only its staging file on noncancellable IO; a deletion failure reports the staging path that may remain and how to remove only that artifact. The original failure or cancellation remains primary with cleanup diagnostics suppressed; without a primary failure, cleanup failure returns an error instead of a success path.

Saved files survive `close_session`, and callers eventually delete them after use. Reports and debugging use the returned absolute path, following the shared [screenshot evidence procedure](../verity/skills/context/procedures.md#screenshot-evidence). Saved MCP files are caller-owned and are not automatically registered as CLI run artifacts.

### Tool Catalog (14 tools)

| Tool | Required params | Returns | Notes |
|------|----------------|---------|-------|
| `open_session` | platform argument or configured default | session_id, device info | Optional: device, disable_animations |
| `close_session` | session_id | confirmation | Restores animations if disabled |
| `list_journeys` | — | formatted list | Optional: path |
| `load_journey` | path | parsed steps | |
| `run_flow` | session_id, yaml | Legacy SUCCESS/FAILED + output or focus-wait JSON | Optional: await_focus_change, focus_timeout_ms |
| `press_key` | session_id, exactly one of key/keycode | Legacy confirmation or focus-wait JSON | Optional: long_press (Android only), await_focus_change, focus_timeout_ms |
| `capture_screenshot` | session_id | base64 JPEG or normalised absolute PNG path | Optional string: save_to_file; no overwrite; caller owns saved file |
| `capture_hierarchy` | session_id | hierarchy text + snapshot_id | Optional: filter (focus/content/all) |
| `capture_focused_tree` | session_id | bounded focused context + full snapshot_id | Optional: filter (focus/content/all); defaults to content |
| `diff_hierarchy` | session_id | bounded structural/focus JSON + resolved IDs | Optional: before_snapshot_id, after_snapshot_id; previous/latest defaults |
| `check_visible` | session_id, text | true/false | Deterministic, case-insensitive |
| `check_focused` | session_id, text | true/false | Lenient ancestor/sibling/child check |
| `run_loop` | session_id, action, until | SATISFIED/NOT + iterations | Optional: max, wait_ms |
| `get_context` | optional path | loaded-file metadata + bundled defaults + markdown context text | Required context can error |


`press_key` accepts an exact JSON string `key` or a non-negative integer JSON `keycode` within JVM `Int` range. Optional `long_press` must be a JSON Boolean and defaults to false. Selector, type, range and focus-option validation happen before session work. Unsupported iOS raw/hold calls fail before hierarchy capture, input or animation waiting. Supported default calls retain their text response and animation wait; focus waiting uses the same baseline/action/wait sequence under one session mutex for every supported selector and hold choice. These arguments extend the shared stdio/HTTP registration without changing journey or loop key resolution; see the [TV controls context](../verity/core/src/main/resources/verity/context/tv-controls.md).

### Focus waits on actions

Both action tools validate focus options before device work. With waiting enabled, one session mutex spans bounded baseline, exactly one action and serial observation. The post-action deadline defaults to 2,000 ms; baseline has its own equal budget. Awaited key presses skip their generic animation wait, and flows retain any explicitly supplied animation commands. Failure prevents post-wait; cancellation remains cancellation even when the SDK wraps an interrupted operation as an ordinary exception.

Awaited JSON separates `action{status,output}` from `focus_changed`, `timed_out`, actual `elapsed_ms`, nullable `focus_before/focus_after` focused lists and nullable `focus_error{code,message}`. Empty focus is `[]`; unknown is null. Baseline failures prevent the action and use distinct error codes. Normal unchanged timeout is a successful tool result, while action/capture errors set `isError`. No full hierarchy snapshots are persisted by waiting. The same registrations serve stdio and HTTP; there are no separate UI, deep-link or notification action entry points. See the [result contract](specs/focus-change-waiting.md#result).

### MCP skill workflows

The repository's [run](../verity/skills/run/SKILL.md), [author](../verity/skills/author/SKILL.md) and [debug](../verity/skills/debug/SKILL.md) files guide an external agent connected through stdio or HTTP. They use the same registered tools and [shared procedures](../verity/skills/context/procedures.md); no host command registration or skill packaging is implied.

`load_journey` parses identity and typed steps, including displaying waits. Semantic wait execution remains CLI-only; MCP run/debug reports the requirement and leaves a wait unexecuted, while author can save reviewed waits as unverified. The caller derives segments, generates Maestro YAML and evaluates semantic assertions against current captures. Run retains confirmation and assertion checkpoints; author reviews editable suggestions before saving current-schema YAML; debug previews every action flow, including setup, before execute/skip/edit choices. Debug reports keep editing separate from passed/failed/skipped outcomes and identify unexecuted segments.

Opened sessions close on completion, stop and failure. Closure attempts saved Android animation-scale restoration when disabling was requested; iOS ignores disabling. App navigation/data and caller-owned saved journeys/screenshots are not restored or removed. Captured evidence references remain distinct from CLI-required result artifacts. See the [MCP skill workflow spec](specs/mcp-skills.md) for the complete caller and cleanup boundary.

### Focused hierarchy capture

`capture_focused_tree` captures the complete hierarchy once under the session mutex and stores it before rendering. Its returned `snapshot_id` identifies the original unfiltered, untruncated tree for `diff_hierarchy`, including attributes, branches and labels omitted from the response. Existing capture-order eviction and `close_session` clearing apply. Both stdio and HTTP discover the tool through the same `create()` catalog.

Context comes from nodes carrying the exact `focused` state; a `focused=true` attribute alone is not focus. For each anchor, include its two nearest ancestors, descendants at distances one and two, and up to two preceding/two following siblings with their immediate children. Merge overlapping positions while preserving source order and parent edges. Capture-local preorder IDs distinguish repeated equal nodes; region headings identify their source depth and ancestors cut above the window. Missing child runs report omission counts and reasons; metadata distinguishes outside-context nodes, omitted candidate context and wholly omitted regions.

Fixed limits are 100 original nodes and 12,000 UTF-16 code units for the entire returned TextContent, including snapshot ID, metadata, headings, indentation and omission/text markers. There are no adjustable limits. Admission ranks focus, ancestors, descendants, then siblings, with preorder ties. Each target and its missing candidate ancestors must fit atomically; ancestry uses the same limits, so even fewer than 100 anchors may not all fit. Skip an unfit bundle and continue. Allocate label text in the same priority order, preserve every included `(focused)` marker, mark shortened rows `[text truncated]`, and avoid splitting surrogate pairs. Structural character omissions retain complete rows and ancestry rather than taking a prefix of the final text.

The optional filter accepts `focus`, `content` and `all`; omitted or unknown values use `content`, matching `capture_hierarchy`. These filters remove attributes, never focused states. `focused_labels_filtered` counts original anchors whose nonempty ALL attributes become empty under the chosen filter; inherently empty labels do not count. It does not mean focus disappeared. `focus_status: no_focus` means the full capture has no focused state and still returns its snapshot ID. With focus present, metadata reports total/included focus and node-limit/character-limit omissions separately, plus node, character and text truncation flags; cap omissions never become `no_focus`.

---

## CLI

### Commands

```
verity run <path>              Execute a journey file or directory suite autonomously
verity list [--path <dir>]     List available journey files
verity mcp [--transport <t>]   Start MCP server (stdio or http)
```

### Shared Options

```
--device <id>            Device ID or IP:port (auto-discover if omitted)
--platform <platform>    Override journey/config platform
--provider <name>        LLM provider (anthropic, openai, google, etc.)
--navigator-model <id>   Model for flow generation (cheap tier)
--inspector-model <id>   Model for assertion evaluation (capable tier)
--navigator-effort <value> Explicit navigator reasoning effort
--inspector-effort <value> Explicit inspector reasoning effort
--api-key <key>          LLM API key (or provider-specific env var)
--journeys-path <path>   Default journey file or directory
--output-path <dir>      Root for generated artifacts
--assertion-strategy <s> infer | visible | focused | tree | visual
--context-path <dir>     Optional path to additional context markdown files
--require-context        Fail if project context is missing or contains no markdown files
--no-animations          Disable device animations during run
--no-bundled-context     Skip bundled context resources
```

The `mcp` subcommand additionally accepts `--host` (default: 127.0.0.1) and `--port` (default: 8080) for HTTP transport.

### Configuration and Result Contracts

CLI defaults, effort-setting precedence and compatibility rules are defined in the [project-configuration spec](specs/project-configuration.md). The [project-context spec](specs/project-context.md) covers `--require-context` and loaded-file reporting.

Normal runs persist their results under the resolved output root. Core owns the serializable result contract, agent owns segment metadata and recorder calls, and CLI owns directory layout, JSON writing, suite aggregation, and exit-code mapping. Suite summaries record each requested effort as explicit or backend-default metadata without claiming which effort a provider actually used. [ADR-0002](adr/0002-required-run-artifacts.md) records why required result writing is part of the command outcome; the [run-artifacts spec](specs/run-artifacts.md) defines the schema and failure boundaries.

---

## Execution Data Flow

### CLI Run

```
File or directory path
    │
    ▼
RunCommand.resolveJourneys() ──→ ordered List<ResolvedJourney>
    │
    ▼
JourneyLoader.fromFile() ──→ Journey(name, app, platform, steps)
    │
    ▼
ContextLoader.loadProject()
    ├── LOADED → report loaded markdown files, inject text into NavigatorAgent
    ├── NOT_CONFIGURED → optional explicit status or required error
    ├── MISSING_DIRECTORY → optional explicit status or required error
    └── EMPTY_DIRECTORY → optional explicit status or required error
    │
    ▼
JourneySegmenter.segment() ──→ List<JourneySegment>
    │
    ▼
Orchestrator.run() loops over segments:
    │
    ├── Wait present? → ConditionWaiter → bounded serial current-state checks
    │
    ├── Actions present?
    │   ├── All map to interactions? → InteractionExecutor
    │   └── Otherwise → NavigatorAgent → ActionFlow → executeActions()
    │
    ├── Assertion present?
    │   ├── VISIBLE → containsText()
    │   ├── FOCUSED → checkFocused()
    │   ├── TREE → captureHierarchy() → InspectorAgent.evaluateTree()
    │   └── VISUAL → captureScreenshot() → InspectorAgent.evaluateVisual()
    │
    ├── Loop present?
    │   └── ConditionEvaluator → complete ordered body via pressKey/executeActions → repeat
    │
    └── Failed? → stop, skip remaining segments
```

Directory inputs discover non-recursive `*.journey.yaml` files in filename order and require one resolved platform. A returned failed journey result allows the suite to continue; an execution exception stops it and preserves completed results. Navigator and inspector model failures abort the suite with exit `5`, retaining completed results and a `model_failure` result for the affected journey and summary. Local validation failures use `setup_failure` and exit `3`. Required result-writing failures take precedence and exit `3`; caller cancellation produces no completed failure result. See the [directory-suite spec](specs/directory-suite-runs.md) and [run-artifact contract](specs/run-artifacts.md).

Before device preflight or connection, the CLI matches any explicit navigator or inspector effort to the selected provider, exact model ID and concrete backend/auth path. An unsupported value reports `provider.effort.unsupported` and exits `3` without device or model work. Validated role parameters are carried to navigator generation and scroll callbacks and inspector tree and visual callbacks. Unset settings use the backend defaults. See the [preflight spec](specs/preflight-checks.md) for the initial supported rows and the [dry-run spec](specs/dry-run.md) for its lazy navigator-only boundary.

### MCP Server

```
AI Agent (Claude Code / Cursor / etc.)
    │
    ▼
MCP Protocol (stdio or HTTP)
    │
    ▼
VerityMcpServer
    ├── open_session → DeviceSessionFactory.connect()
    ├── press_key → validate key XOR keycode / long_press → session.pressKey()
    ├── run_flow → session.executeFlow()
    ├── capture_screenshot
    │   ├── inline → capture PNG → compress → base64 JPEG → delete temporary files
    │   └── save_to_file → normalise/preflight → sibling PNG → validate → publish → absolute path
    ├── capture_hierarchy → session.captureHierarchyTree() → snapshot store → filtered text
    ├── capture_focused_tree → one full capture → snapshot store → bounded focused forest
    ├── diff_hierarchy → session admission → snapshotStore.resolvePair() → HierarchyDiff.render()
    ├── check_visible → session.containsText()
    ├── check_focused → session.checkFocused()
    ├── run_loop → pressKey() loop with condition check
    └── close_session → restore animations → session.close() → clear snapshots
```

---

## Design Principles

1. **Cost-aware assertions**: Free deterministic checks before cheap text LLM before expensive vision LLM. The `AssertModeInferrer` and `[?]` syntax make the cheapest mode the default path.

2. **Interaction mapping**: Recognised actions bypass LLM flow generation. Off-screen named targets may still require navigator reasoning to find them.

3. **Persistent connections**: Embedded Maestro SDK with persistent gRPC (Android) and HTTP (iOS) connections. No process spawning per operation.

4. **Segment-based execution**: Splitting at assertion boundaries creates natural checkpoints. Each segment is independent — clear failure attribution, debuggable output.

5. **Segment isolation**: Fresh navigator and inspector instances keep reasoning scoped to the current segment.

6. **Platform abstraction**: One `DeviceSession` interface, platform-specific implementations. Core logic (parsing, segmentation, interaction mapping) is platform-aware but SDK-free.

7. **Dual mode from one core**: The same device and core layers serve both autonomous CLI execution and interactive MCP-driven workflows. Author interactively, run in CI.

## OpenAI API and ChatGPT backends

The CLI owns `SelectedRoleModel`: either the native API `LLModel` or an exact Codex ID, with one selected model per role. `ModelRequestBackend` adapts the existing navigator and inspector callbacks without changing typed `ActionFlow` decoding, prompts, inspector verdicts or scroll handling. API requests carry the complete preflight `LLMParams` through Koog, including endpoint-specific data. ChatGPT requests carry only exact catalog-validated optional effort. There is no provider registry or new module.

ChatGPT preflight owns acquisition until success transfers one prepared backend to the run or lazy preview owner. Acquisition, requests and cleanup have separate deadlines. Ownership spans failed device connection, suite abort, report writing and caller cancellation. Normal requests and lazy preview use fresh ephemeral Codex threads; model-owned request deadlines retain the agent's stage and failure policy. The canonical `ModelBackendFailure` in agent carries only fixed kinds, without raw causes; request conversion preserves the caller's stage, observed wait failure timing and cancellation.

Preparation uses a discovery-only bootstrap to read inherited MCP/plugin/app names, closes it, then launches the final process with each name disabled and verifies effective policy. Launch and thread policy pin Codex's default ChatGPT origins (`chatgpt_base_url=https://chatgpt.com/backend-api/`, `openai_base_url=https://chatgpt.com/backend-api/codex`), and effective-policy verification fails closed with `codex.isolation` when a higher-precedence layer such as managed configuration reports another origin. Both child environments remove `OPENAI_API_KEY`, `CODEX_API_KEY` and the origin-override variables `OPENAI_BASE_URL`, `CODEX_APP_SERVER_CHATGPT_BASE_URL`, `CODEX_REFRESH_TOKEN_URL_OVERRIDE` and `CODEX_REVOKE_TOKEN_URL_OVERRIDE`, use owned empty temporary working directories, and retain Codex's existing authentication/runtime-store location. Verity does not read, copy, migrate, modify or delete credential/runtime files, invoke login/logout/config writes, or redirect `CODEX_HOME`, `sqlite_home` or `CODEX_SQLITE_HOME`.

Launch and thread policy disables tools, approvals, callbacks, delegation, integration capabilities and network-enabled execution. Experimental raw events are required. Every ordinary/raw tool attempt and every server request, including unknown methods and authentication-refresh callbacks, permanently invalidates the request even if a later assistant message looks valid. No execution callback is implemented.

Empty working directories and `project_doc_max_bytes=0` do not establish that inherited global `AGENTS.md` guidance is absent: the historical probe reported its source. Codex retains its runtime database ownership and may perform its own metadata/backfill operations. This backend is not wholly stateless. See the [version/host-bound checkpoint](https://github.com/chrisbanes/verity/issues/92#issuecomment-5955708208), [official app-server docs](https://learn.chatgpt.com/docs/app-server), and [configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference). Fake-child regression tests establish owned process exit and directory removal separately from that historical real-server evidence.

MCP remains device-only under [ADR-0001](adr/0001-mcp-device-boundary.md). Shared `list`/MCP configuration resolution starts no Codex process. Fast-only dry-run remains lazy and device/model-free; only an unmapped group triggers navigator-only preflight.
