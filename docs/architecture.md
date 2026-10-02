# Verity Architecture

Verity is a Kotlin/JVM tool that combines device automation (Maestro SDK) with LLM reasoning (via Koog) to run end-to-end journey tests on Android TV, Android mobile, and iOS devices. It operates in two modes:

1. **CLI mode** (`verity run`) — Executes journey YAML files against a connected device, using LLMs to generate Maestro flows and evaluate assertions. `verity run --dry-run` parses, segments, renders fast-path actions, and generates slow-path Maestro YAML without device access.
2. **MCP server mode** (`verity mcp`) — Exposes device control as MCP tools so an AI agent can interactively drive the device.

LLMs serve two purposes: flow generation (turning English into Maestro YAML) and assertion evaluation (judging whether screen state matches an expectation). Deterministic interactions and assertions bypass those calls when possible.

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
| `:verity:core` | nothing (kotlinx.serialization, Kaml) | Models, journey format, step parsing, segmenter, interaction mapper, hierarchy renderer, assertion mode inferrer |
| `:verity:device` | `:verity:core` | `DeviceSession` interface and platform-specific implementations (Android via Dadb + Maestro gRPC, iOS via Maestro XCTest HTTP) |
| `:verity:agent` | `:verity:core`, `:verity:device` | Koog LLM setup, NavigatorAgent, InspectorAgent, Orchestrator |
| `:verity:mcp` | `:verity:core`, `:verity:device` | MCP server (stdio + HTTP), 12 tools, session manager, snapshot store |
| `:verity:cli` | `:verity:agent`, `:verity:mcp` | Clikt commands: `run`, `list`, `mcp` |
| `:verity:smoke-tests` | `:verity:cli` | Device smoke tests (Android emulator) |

**Key rule:** `:verity:mcp` does not depend on `:verity:agent`. The MCP server exposes raw device capabilities — the external AI agent provides the intelligence.

---

## Technology Stack

| Component | Library | Purpose |
|-----------|---------|---------|
| CLI | Clikt | Argument parsing, subcommands |
| YAML | Kaml | Journey deserialization with custom serializers |
| Serialization | kotlinx.serialization | JSON/YAML encoding/decoding |
| LLM | Koog (JetBrains) | Prompt DSL, model abstraction, provider-agnostic |
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
- Pinning `io.grpc` artifacts to a single version

---

## Project Configuration

The CLI owns loading and resolving `verity/config.yaml`. Shared resolution applies CLI input first, project defaults second, and built-in defaults last; structured LLM settings preserve compatibility with legacy top-level keys. The resolved configuration supplies defaults to `run`, `list`, and the CLI command that starts MCP.

See the [project-configuration spec](specs/project-configuration.md) for the schema, per-command settings, validation, and assertion strategy. Runtime project-context loading is separate from the repository's domain glossary; its required/optional behavior is defined in the [project-context spec](specs/project-context.md).

---

## Journey Format

Journeys are YAML files describing user flows. File extension: `*.journey.yaml`.

```yaml
name: Browse home and open detail
app: com.example.launcher
platform: android-tv

steps:
  - Launch the app
  - [?] Home
  - Navigate down until TV Shows row
  - Press select
  - [?] Detail page shows title
  - [?visual] Backdrop image loads
  - [?focused] Settings menu item
  - [?tree] Synopsis contains at least 2 sentences
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

Steps are parsed through a 5-stage priority chain:

1. **`[?mode]` prefix** — `[?visual]`, `[?tree]`, `[?visible]`, `[?focused]` locks the assertion mode
2. **`[?]` prefix** — Assert with mode chosen by `AssertModeInferrer`
3. **Loop inference** — NL pattern: `<verb> ... until <condition>`
4. **Assertion inference** — NL keywords: "Verify...", "Ensure...", "Confirm...", "Check..."
5. **Default** — Action

### Assert Mode Inference

The `AssertModeInferrer` selects the cheapest sufficient mode:
- Contains visual keywords (color, colour, highlight, image, icon, animation, gradient, blur, backdrop, thumbnail, poster, artwork, badge, logo, overlay, opacity, shadow, border) → `VISUAL`
- 3 words or fewer, no visual keywords → `VISIBLE`
- Everything else → `TREE`

### Loop Inference

The `LoopStepInferrer` matches patterns like `<verb> ... until <condition> [up to N times]`:
- Leading verb must be: press, navigate, move, scroll, go, step
- The action part is normalized to platform key names

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
}

enum class AssertMode { VISIBLE, FOCUSED, TREE, VISUAL }

enum class Platform { ANDROID_TV, ANDROID_MOBILE, IOS }

data class JourneySegment(
    val index: Int,
    val actions: List<JourneyStep.Action>,
    val assertion: JourneyStep.Assert? = null,
    val loop: JourneyStep.Loop? = null
)
```

### Segmentation

`JourneySegmenter` splits steps into independently executable segments:
- Actions accumulate until an assertion → segment with those actions + assertion
- Loops flush pending actions as a separate segment, then become their own segment
- Trailing actions without an assertion become a final segment

Each segment is a natural checkpoint: run actions, evaluate assertion, stop on failure.

### Dry-Run Planning

Dry run is a CLI-owned planning path. `RunCommand` uses the normal journey resolver, then `DryRunPlanner` segments steps and classifies mapped interactions. Navigator creation is lazy: only slow-path actions or loops generate Maestro YAML. Assertions are reported without evaluation.

The planner has no device-session dependency and does not invoke `Orchestrator`. See the [dry-run spec](specs/dry-run.md) for device boundaries, provider checks, Markdown output, and the current artifact side effects of shared input resolution.

---

## Device Layer

### Interface

```kotlin
interface DeviceSession : AutoCloseable {
    val platform: Platform

    suspend fun executeFlow(yaml: String): FlowResult
    suspend fun pressKey(keyName: String)
    suspend fun captureHierarchyTree(): HierarchyNode          // abstract
    suspend fun captureScreenshot(output: Path)
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

### Implementations

**`AndroidDeviceSession`**: Connects via Dadb (ADB over TCP). Creates a Maestro instance with persistent gRPC connection. Supports device ID, IP:port, or auto-discovery.

**`IosDeviceSession`**: Installs XCTest runner on device/simulator. Communicates via HTTP to the on-device XCTest server (localhost:22087). Simulator management via `xcrun simctl`, physical devices via `devicectl`.

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
| **Navigator** (Cheap) | YAML generation | `claude-haiku-4-5`, `gemini-3.0-flash`, `gpt-5-mini` |
| **Inspector** (Capable) | Assertion evaluation | `claude-sonnet-4-6`, `gemini-3.1-pro`, `gpt-5.4` |

Configured through Koog — can swap providers by updating the executor and model ID.

The Google provider defaults to `gemini-2.5-flash-lite` for navigation and `gemini-2.5-pro` for inspection. `gemini-3.1-pro-preview` is available as an explicit model selection.

### NavigatorAgent

Converts natural language actions to Maestro YAML. Receives the target `Platform` and adjusts output accordingly (D-pad commands for TV, tap/swipe for mobile, iOS gestures for iOS).

System prompt instructs: generate only valid Maestro YAML, no explanation, add `waitForAnimationToEnd` after navigation, use `extendedWaitUntil` for content that needs loading time.

### InspectorAgent

Evaluates assertions against screen state using constructor-injected agent factories (no internal model selection):
- `evaluateTree(hierarchy, assertion)` — text-only evaluation
- `evaluateVisual(screenshotPath, assertion)` — vision-enabled evaluation

Returns `InspectionVerdict(passed: Boolean, reasoning: String)`. Lenient JSON parsing with code fence stripping.

### Orchestrator

Runs journeys segment by segment using a **subagent pattern** to keep context windows small and focused. Each segment is treated as a discrete task for a fresh agent instance, preventing the accumulation of history from unrelated segments.

**Action execution (two paths):**
- Fast path: all actions map via `InteractionMapper` → `InteractionExecutor`, with scroll-to-find reasoning for off-screen named targets
- Slow path: `NavigatorAgent` generates Maestro YAML → `executeFlow()`

**Assertion evaluation (four modes):**

| Mode | Method | Cost |
|------|--------|------|
| VISIBLE | `containsText()` — substring match | Free |
| FOCUSED | `checkFocused()` — lenient tree walk | Free |
| TREE | `InspectorAgent.evaluateTree()` | Medium |
| VISUAL | `InspectorAgent.evaluateVisual()` | High |

Each segment creates fresh navigator and inspector instances. Their reasoning is scoped to that segment rather than carrying an accumulated conversation through the journey.

---

## MCP Server

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

LRU map of captured `HierarchyNode` trees (capped at 10 per session). Supports future diff functionality without refactoring.

### Screenshot Compression

For MCP transport: read PNG, scale to max 1280px width (bilinear interpolation), encode as JPEG at 0.75 quality, return as base64.

### Tool Catalog (12 tools)

| Tool | Required params | Returns | Notes |
|------|----------------|---------|-------|
| `open_session` | platform argument or configured default | session_id, device info | Optional: device, disable_animations |
| `close_session` | session_id | confirmation | Restores animations if disabled |
| `list_journeys` | — | formatted list | Optional: path |
| `load_journey` | path | parsed steps | |
| `run_flow` | session_id, yaml | SUCCESS/FAILED + output | Optional: await_focus_change |
| `press_key` | session_id, key | confirmation | Optional focus change result |
| `capture_screenshot` | session_id | base64 JPEG or file path | Optional: save_to_file |
| `capture_hierarchy` | session_id | hierarchy text + snapshot_id | Optional: filter (focus/content/all) |
| `check_visible` | session_id, text | true/false | Deterministic, case-insensitive |
| `check_focused` | session_id, text | true/false | Lenient ancestor/sibling/child check |
| `run_loop` | session_id, action, until | SATISFIED/NOT + iterations | Optional: max, wait_ms |
| `get_context` | optional path | loaded-file metadata + bundled defaults + markdown context text | Required context can error |

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

CLI defaults and compatibility rules are defined in the [project-configuration spec](specs/project-configuration.md). The [project-context spec](specs/project-context.md) covers `--require-context` and loaded-file reporting.

Normal runs persist their results under the resolved output root. Core owns the serializable result contract, agent owns segment metadata and recorder calls, and CLI owns directory layout, JSON writing, suite aggregation, and exit-code mapping. [ADR-0002](adr/0002-required-run-artifacts.md) records why required result writing is part of the command outcome; the [run-artifacts spec](specs/run-artifacts.md) defines the schema and failure boundaries.

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
    ├── Actions present?
    │   ├── All map to interactions? → InteractionExecutor
    │   └── Otherwise → NavigatorAgent → executeFlow()
    │
    ├── Assertion present?
    │   ├── VISIBLE → containsText()
    │   ├── FOCUSED → checkFocused()
    │   ├── TREE → captureHierarchy() → InspectorAgent.evaluateTree()
    │   └── VISUAL → captureScreenshot() → InspectorAgent.evaluateVisual()
    │
    ├── Loop present?
    │   └── checkCondition → pressKey/executeFlow → repeat
    │
    └── Failed? → stop, skip remaining segments
```

Directory inputs discover non-recursive `*.journey.yaml` files in filename order and require one resolved platform. A returned failed journey result allows the suite to continue; an execution exception stops it and preserves completed results. See the [directory-suite spec](specs/directory-suite-runs.md).

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
    ├── press_key → session.pressKey()
    ├── run_flow → session.executeFlow()
    ├── capture_screenshot → session.captureScreenshot() → compress → base64
    ├── capture_hierarchy → session.captureHierarchy() → snapshot store
    ├── check_visible → session.containsText()
    ├── check_focused → session.checkFocused()
    ├── run_loop → pressKey() loop with condition check
    └── close_session → restore animations → session.close()
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
