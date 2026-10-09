# Verity

[![Nice](https://api.nice.sbs/badge/n_rNVAX1s22Wrk.svg?theme=rich)](https://nice.sbs/button?id=n_rNVAX1s22Wrk)

> **Preview** — Verity is under active development and not yet ready for general use.

Verity is an end-to-end testing tool that combines device automation with LLM reasoning. Write human-readable journey files, and Verity executes them against real devices — navigating UIs, pressing buttons, and verifying what's on screen.

## Platforms

- Android TV
- Android Mobile
- iOS

## Installation and host choice

Verity requires Java 21 or later. Choose the archive for the computer running
Verity, independently of the Android device's architecture:

| Host | Archive |
| --- | --- |
| macOS ARM64 | `verity-V-macos-aarch64.jar` |
| Linux x86-64 | `verity-V-linux-x86_64.jar` |
| Existing universal fallback | `verity-V.jar` |

`V` is the release version. The matching-host archives omit unrelated native
resources; both retain the complete Android APKs. macOS and universal retain
complete iOS driver bundles. The universal fallback does not qualify additional
hosts or new physical-iOS support.

The release workflow is wired for future version tags; this change has not
published a release. To build the archives from this checkout:

```sh
./gradlew :verity:cli:hostJars --no-scan
java -jar verity/cli/build/libs/verity-0.1.0.jar --help
```

For a published version, download the chosen JAR and
`verity-V-checksums.sha256` from the same release. Compare `shasum -a 256 JAR`
(on macOS) or `sha256sum JAR` (on Linux) with the manifest line bearing that exact
filename before running `java -jar JAR --help`. Replace `JAR` with the downloaded
filename; the checksum manifest lists all three archives.

The Homebrew formula selects the macOS ARM64 or Linux x86-64 asset on matching
hosts and otherwise uses the universal asset. It downloads the JAR without
unpacking it, installs the selected basename as `libexec/verity.jar`, and launches
that canonical file with Homebrew's Java 21. The future release workflow updates
the tap only after verifying the published assets; it does not establish new
host support. See the [host-packaging specification](docs/specs/host-packaging.md)
for the matrix, checksums, measurements and qualification limits.

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

For interactive MCP work, give your agent the [run](verity/skills/run/SKILL.md), [author](verity/skills/author/SKILL.md) or [debug](verity/skills/debug/SKILL.md) workflow file after connecting it to a running `verity mcp` server. Run confirms and executes a journey; author reviews suggestions and saves journey YAML; debug previews each segment and lets you execute, skip or edit it. See the [workflow reference](docs/specs/mcp-skills.md) and [shared procedures](verity/skills/context/procedures.md). The repository files do not automatically register host slash commands.

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

### OpenAI authentication

OpenAI uses API-key authentication by default. To use an existing Codex ChatGPT login on macOS, select both the provider and auth mode:

```bash
verity --provider openai --openai-auth chatgpt run journeys/login.journey.yaml
```

Sign in through Codex first. Verity requires Codex CLI 0.159.0 or newer and checks the required app-server schema and isolation; a newer version alone does not establish compatibility. Codex 0.161.0 is runtime-qualified on macOS arm64; 0.159.0 is source-verified only. ChatGPT defaults both roles to `gpt-6-luna`, subject to the dynamic catalog. Explicit model IDs and efforts must match that catalog exactly. See [configuration](docs/specs/project-configuration.md#openai-authentication) and [preflight](docs/specs/preflight-checks.md#chatgpt-preflight) for support limits and remediation.
