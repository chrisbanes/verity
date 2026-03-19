# Verity Architecture

Verity is a Kotlin/JVM tool that combines device automation (Maestro SDK) with LLM reasoning (via Koog) to run end-to-end journey tests on Android TV, Android mobile, and iOS devices. It operates in two modes:

1. **CLI mode** (`verity run`) — Executes journey YAML files against a connected device, using LLMs to generate Maestro flows and evaluate assertions.
2. **MCP server mode** (`verity mcp`) — Exposes device control as MCP tools so an AI agent can interactively drive the device.

LLMs serve two purposes: flow generation (turning English into Maestro YAML) and assertion evaluation (judging whether screen state matches an expectation). Deterministic fast-paths handle both when possible, so LLM calls happen only when necessary.

---

## Module Dependency Graph

```
:verity:core  <── :verity:device  <── :verity:agent  <── :verity:cli
                        ^                                      |
                   :verity:mcp  <──────────────────────────────┘
```

| Module | Depends on | Purpose |
|--------|-----------|---------|
| `:verity:core` | nothing (kotlinx.serialization, Kaml) | Models, journey format, step parsing, segmenter, key mapper, hierarchy renderer, assertion mode inferrer |
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

### Platform Key Mapper

`PlatformKeyMapper` maps natural language to platform key codes. Per-platform implementations, all in `:verity:core` (string-to-string mapping, no device SDK dependency):

| Platform | Example mapping |
|----------|----------------|
| Android TV | "press d-pad down" → "Remote Dpad Down" |
| Android Mobile | "press back" → "back" |
| iOS | "press home" → home gesture mapping |

When all actions in a segment map to known keys, the orchestrator bypasses LLM flow generation and presses keys directly.

---

## Agent Layer

### LLM Configuration

Verity uses a tiered model strategy via Koog. While Claude models are the recommended default for their strong reasoning and XML/YAML compliance, the system is provider-agnostic.

| Tier | Task | Suggested Models |
|------|------|------------------|
| **Navigator** (Cheap) | YAML generation | `claude-haiku-4-5`, `gemini-3.0-flash`, `gpt-5-mini` |
| **Inspector** (Capable) | Assertion evaluation | `claude-sonnet-4-6`, `gemini-3.1-pro`, `gpt-5.4` |

Configured through Koog — can swap providers by updating the executor and model ID.

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
- Fast path: all actions map via `PlatformKeyMapper` → direct `pressKey()` calls
- Slow path: `NavigatorAgent` generates Maestro YAML → `executeFlow()`

**Assertion evaluation (four modes):**

| Mode | Method | Cost |
|------|--------|------|
| VISIBLE | `containsText()` — substring match | Free |
| FOCUSED | `checkFocused()` — lenient tree walk | Free |
| TREE | `InspectorAgent.evaluateTree()` | Medium |
| VISUAL | `InspectorAgent.evaluateVisual()` | High |

**Context Optimization:** By isolating each segment's reasoning into sequential subagent calls, Verity supports extremely long journeys that would otherwise exceed model context limits or cause "context drift" where the model conflates different parts of a long journey.

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
| `open_session` | platform | session_id, device info | Optional: device, disable_animations |
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
| `get_context` | — | bundled defaults + markdown context text | Optional: path |

---

## CLI

### Commands

```
verity run <journey>           Execute a journey autonomously
verity list [--path <dir>]     List available journey files
verity mcp [--transport <t>]   Start MCP server (stdio or http)
```

### Shared Options

```
--device <id>            Device ID or IP:port (auto-discover if omitted)
--platform <platform>    android-tv | android | ios (default: android-tv)
--provider <name>        LLM provider (anthropic, openai, google, etc.)
--navigator-model <id>   Model for flow generation (cheap tier)
--inspector-model <id>   Model for assertion evaluation (capable tier)
--api-key <key>          LLM API key (or ANTHROPIC_API_KEY env var)
--context-path <dir>     Optional path to additional context markdown files
--no-animations          Disable device animations during run
--no-bundled-context     Skip bundled context resources
```

The `mcp` subcommand additionally accepts `--host` (default: 127.0.0.1) and `--port` (default: 8080) for HTTP transport.

---

## Execution Data Flow

### CLI Run

```
Journey YAML
    │
    ▼
JourneyLoader.load() ──→ Journey(name, app, platform, steps)
    │
    ▼
JourneySegmenter.segment() ──→ List<JourneySegment>
    │
    ▼
Orchestrator.run() loops over segments:
    │
    ├── Actions present?
    │   ├── All map to keys? → direct pressKey() calls
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

2. **Fast-path key mapping**: Direct key presses for navigation bypass LLM flow generation entirely. Covers the most common actions (D-pad navigation on TV, basic gestures on mobile).

3. **Persistent connections**: Embedded Maestro SDK with persistent gRPC (Android) and HTTP (iOS) connections. No process spawning per operation.

4. **Segment-based execution**: Splitting at assertion boundaries creates natural checkpoints. Each segment is independent — clear failure attribution, debuggable output.

5. **Subagent isolation**: Each segment is executed by an isolated agent session. This drastically reduces context window usage and eliminates the risk of "history hallucination" where the model conflates different parts of a long journey.

6. **Platform abstraction**: One `DeviceSession` interface, platform-specific implementations. Core logic (parsing, segmentation, key mapping) is platform-aware but SDK-free.

7. **Dual mode from one core**: The same device and core layers serve both autonomous CLI execution and interactive MCP-driven workflows. Author interactively, run in CI.
