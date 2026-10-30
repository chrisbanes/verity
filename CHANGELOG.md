# Changelog

All notable changes to Verity are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- Opt-in pre-check for loop and wait conditions (`run --precheck` or
  `precheck.enabled`): a fast classifier answers before the inspector, using
  TypeSafe System One by default or OpenAI's Decisions API with
  `precheck.provider: decisions`. Confident passes are confirmed by the
  inspector unless `precheck.confirm-pass: false`. An experimental screenshot
  pre-check covers `visually` conditions (`precheck.visual-enabled`).
- Run a whole directory of journeys as a suite, with persisted artifacts and CI
  results (`--output-path`).
- `run --dry-run` to preview a journey without driving a device.
- `run --timing` for per-step model and device durations, and
  `run --model-trace` for diagnosing model backend failures.
- Wait steps with a single evaluation deadline, and semantic loop conditions
  with ordered loop bodies.
- Project configuration defaults (`--journeys-path`, `--assertion-strategy`,
  `--context-path`, `--require-context`) and project context validation.
- Shared preflight checks for CLI and MCP workflows.
- OpenAI ChatGPT authentication through Codex (`--openai-auth chatgpt`).
- Per-role reasoning effort (`--navigator-effort`, `--inspector-effort`).
- Raw Android keycodes, long presses, and named key actions resolved from
  project context.
- Wireless `IP:port` ADB serials and platform wire names in `--device` and
  `--platform`.
- MCP hierarchy snapshot diffing, bounded focused hierarchy capture, and
  waiting for observed focus changes.
- Bounded journey memory and an execution trail for inspections.
- Prose-like focus conditions in loop and wait steps now produce a warning.
- CLI packages for macOS and Linux, shrunk with R8.

### Changed

- Inferred assertions default to the `tree` strategy; `visually` takes
  precedence over the configured strategy.
- Android TV journeys are much faster: key presses settle on hierarchy
  stability, focus-changing D-pad presses skip the animation wait, navigation
  loops batch D-pad presses (including estimated loops where focus does not
  change), and a generated relaunch right after setup is skipped.
- The navigator receives the current hierarchy, uses a compact rendering, and
  retries once when generated actions fail validation.
- Quoted wait and loop conditions stay on the literal tier.
- Android devices are woken before a run, and runs fail early if the device is
  asleep or dreaming.
- The device preflight runs alongside Codex startup, and iOS reuses the
  preflight's booted simulator.
- Codex children run in an owned `CODEX_HOME`.
- Updated Maestro to 2.12.0, Koog to 1.3.0, Kotlin to 2.4.21, and other
  dependencies.

### Fixed

- Model backend protocol failures are reported separately from invalid model
  responses, and navigator model failures are handled before execution.
- Several Codex app-server protocol issues, including a thread-unload race that
  could overflow the frame queue and unload notifications from foreign threads.
- MCP screenshot saves are atomic.
- iOS captures wait for launch to settle and respect the caller's time budget.
- Android named short key presses route through `input keyevent`.
- Issues with `--no-animations` and visual assertion inference.

## [0.0.2] - 2026-03-23

### Added

- Touch fast path for mobile journeys (`InteractionMapper`).
- Android emulator and iOS simulator device smoke tests.
- Architecture documentation.

### Changed

- The iOS backend uses `XCTestIOSDevice`.

### Fixed

- Flaky iOS XCTest driver connections are retried in smoke tests.
- Various code review issues.

## [0.0.1] - 2026-03-17

Initial release: the core journey model and parsers, Android TV, Android
mobile and iOS device sessions, the LLM navigator and inspector agents, the MCP
server, the CLI with multi-provider LLM support, bundled skills and context,
and Homebrew distribution.

[Unreleased]: https://github.com/chrisbanes/verity/compare/v0.0.2...HEAD
[0.0.2]: https://github.com/chrisbanes/verity/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/chrisbanes/verity/releases/tag/v0.0.1
