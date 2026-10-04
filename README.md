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
platform: android-tv

steps:
  - Navigate down to the 'Continue Watching' row
  - "[?tree] A 'Continue Watching' row is visible with at least one item"
  - Select the first item
  - "[?tree] The detail screen shows a title and a 'Play' button"
```

Verity splits each journey into segments and drives the device through the Maestro SDK, using mapped interactions or generated flows. Assertions use visible-text or focus checks, accessibility-tree reasoning, or screenshot evaluation according to their selected mode.

## Modes

- **CLI** (`verity run`) — Run journey files autonomously against connected devices.
- **MCP server** (`verity mcp`) — Expose device control as MCP tools for interactive AI workflows (e.g., in Claude Code).

See the [documentation index](docs/README.md) for the domain glossary, architecture decisions, and behavior specifications, including suite runs, configuration, dry-run previews, and CI artifacts.

## Architecture

```
core  ←  device  ←  agent  ←  cli
              ↑                  |
             mcp  ←─────────────┘
```

| Module | Role |
|--------|------|
| `core` | Journey models, YAML parsing, segmentation, interaction mapping. Zero device or LLM dependencies. |
| `device` | Device abstraction layer — Android via ADB + Maestro gRPC, iOS via Maestro XCTest HTTP. |
| `agent` | LLM orchestration — navigator and inspector agents, journey execution. |
| `mcp` | MCP server exposing raw device tools. Does not depend on `agent`. |
| `cli` | Entry point — `run`, `list`, and `mcp` commands. |

## Key design choices

- **Interaction mapping** — Recognised actions use device interactions directly; other actions use generated Maestro flows. Finding an off-screen named target can still require navigator reasoning.
- **Segment isolation** — Each segment uses fresh navigator and inspector instances, keeping their reasoning scoped to that segment.
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

### Wait for focus after an MCP action

Both `press_key` and `run_flow` support `await_focus_change: true` and optional `focus_timeout_ms` (default 2,000). For example, pass `{"session_id":"<id>","key":"DPAD_DOWN","await_focus_change":true,"focus_timeout_ms":2000}` to `press_key`. The JSON response preserves the action outcome and reports the observed focus change or timeout, elapsed wait and focused path/resource evidence. See [focus-change waiting](docs/specs/focus-change-waiting.md) for errors, identity rules and flow-level observation limits.
