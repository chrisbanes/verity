# Verity

[![Nice](https://api.nice.sbs/badge/n_rNVAX1s22Wrk.svg?theme=rich)](https://nice.sbs/button?id=n_rNVAX1s22Wrk)

> **Preview** — Verity is under active development and not yet ready for general use.

Verity is an end-to-end testing tool that combines device automation with LLM reasoning. Write human-readable journey files, and Verity executes them against real devices — navigating UIs, pressing buttons, and verifying what's on screen.

## Platforms

- Android TV
- Android Mobile
- iOS

## How it works

Journeys are YAML files that describe what a user does and what the app should show:

```yaml
name: Launch and browse
app: com.example.tv
platform: android_tv

steps:
  - action: "Launch the app"
  - action: "Navigate down to the 'Continue Watching' row"
  - assert: "A 'Continue Watching' row is visible with at least one item"
  - action: "Select the first item"
  - assert: "The detail screen shows a title and a 'Play' button"
```

Verity splits each journey into segments, hands each segment to an isolated LLM agent, and drives the device through the Maestro SDK. Assertions use a cost-aware strategy: deterministic checks first, then text-based reasoning, then vision — so you pay only for what each assertion requires.

## Modes

- **CLI** (`verity run`) — Run journey files autonomously against connected devices.
- **MCP server** (`verity mcp`) — Expose device control as MCP tools for interactive AI workflows (e.g., in Claude Code).

## Architecture

```
core  ←  device  ←  agent  ←  cli
              ↑                  |
             mcp  ←─────────────┘
```

| Module | Role |
|--------|------|
| `core` | Journey models, YAML parsing, segmentation, key mapping. Zero device or LLM dependencies. |
| `device` | Device abstraction layer — Android via ADB + Maestro gRPC, iOS via Maestro XCTest HTTP. |
| `agent` | LLM orchestration — navigator and inspector agents, journey execution. |
| `mcp` | MCP server exposing raw device tools. Does not depend on `agent`. |
| `cli` | Entry point — `run`, `list`, and `mcp` commands. |

## Key design choices

- **Fast-path key mapping** — Common navigation actions (D-pad, gestures) go straight to the device, bypassing LLM generation entirely.
- **Subagent isolation** — Each journey segment runs in its own LLM session, keeping context windows small and preventing hallucination from long histories.
- **Persistent connections** — Embedded Maestro SDK holds persistent gRPC/HTTP connections to devices instead of spawning processes per operation.

## Manual smoke checklist

Minimal path to verify real-device integration:

1. **Start MCP server**
   ```bash
   ./gradlew :verity:cli:run --args="mcp --transport stdio"
   # or: --args="mcp --transport http --port 8080"
   ```
2. **Open session** — call `open_session` with `platform: android-tv` (or `android`/`ios`)
3. **Press a key** — call `press_key` with `key: DPAD_DOWN`
4. **Check visibility** — call `check_visible` with `text: <visible UI text>`
5. **Capture hierarchy** — call `capture_hierarchy` with `filter: content` and verify the tree renders
6. **Close session** — call `close_session` and verify device state is restored
