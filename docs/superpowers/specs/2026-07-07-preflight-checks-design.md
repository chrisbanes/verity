# Shared Preflight Checks Design

## Context

GitHub issue 45 asks for a shared preflight layer that validates the local environment before CLI and MCP workflows reach low-level runtime failures.

Today `verity run` performs a few inline checks for the journey file and provider API key, then creates the device session and LLM client directly. MCP catches thrown exceptions and returns generic error text. Device discovery errors are raised inside `DeviceSessionFactory`, which means callers cannot consistently identify missing ADB, missing `xcrun`, unavailable devices, or simulator state problems.

## Goals

- Give CLI commands actionable non-zero failures before opening device sessions or LLM clients.
- Give MCP callers structured, actionable preflight errors before opening sessions.
- Share platform, runtime, and path preflight behavior between CLI and MCP.
- Keep LLM provider, model, and credential checks CLI-only because MCP currently exposes raw device tools and does not own LLM execution.
- Cover behavior with tests using fake platform, process, provider, and filesystem checks.

## Non-Goals

- Do not add LLM provider checks to MCP.
- Do not change MCP from raw device tooling into an agent or journey runner.
- Do not perform network calls to validate API keys or remote model availability.
- Do not open real device sessions during preflight tests.

## Architecture

Add dependency-light preflight result types to `:verity:core`:

- `PreflightReport`
- `PreflightIssue`
- `PreflightSeverity`
- stable issue codes such as `android.adb.missing`, `android.device.missing`, `ios.xcrun.missing`, `ios.simulator.none`, `provider.credential.missing`, and `path.not_writable`

`PreflightReport` should expose whether blocking errors exist and preserve all warnings so callers can render full context.

Add device and runtime checkers to `:verity:device`:

- `AndroidPreflightChecker` validates explicit Android device IDs, ADB availability, and Android device discovery.
- `IosPreflightChecker` validates `xcrun`, `simctl list devices booted -j`, no-device state, and multiple-booted-simulator state.
- Checkers use constructor or function injection for command execution and discovery so unit tests do not require ADB, Xcode, simulators, or physical devices.

Keep CLI-only checks in `:verity:cli`:

- provider resolution
- navigator and inspector model resolution
- required credential presence
- Bedrock secondary credential presence
- journey/config/context path readability

Add shared filesystem/path checks where both CLI and MCP need them. If the checks have no CLI-specific dependencies, place them in `:verity:core`; otherwise keep thin adapters in each caller.

## CLI Flow

`RunCommand` should build a preflight request from resolved CLI/config inputs:

1. Load config if present.
2. Resolve provider and models through preflight-safe functions that return issues instead of throwing raw `IllegalStateException`.
3. Validate credentials for auth-required providers.
4. Validate journey and context paths.
5. Load the journey only after the journey path passes basic file checks.
6. Determine the selected platform from `--platform` or the journey.
7. Run platform preflight.
8. If the report has errors, throw a Clikt error that renders issue messages and remediation steps.
9. If the report passes, proceed with the existing session, LLM client, and orchestrator setup.

CLI warnings may be printed before execution, but errors must fail with a non-zero exit.

## MCP Flow

`open_session` should run device preflight before calling `McpDeviceSessionManager.open`.

MCP failures should return `CallToolResult(isError = true)` with structured text containing each issue's code, message, and remediation. The shape can be JSON text if the MCP SDK does not offer a stronger structured error field. Generic exception handling should remain as a fallback, but expected preflight failures should not be formatted as `"ExceptionType: message"`.

MCP should not check provider, model, or credential configuration.

## Error Contract

Each preflight issue should contain:

- `code`: stable identifier for tests and MCP clients
- `severity`: error or warning
- `message`: one clear sentence describing the failure
- `remediation`: one clear next action
- `details`: optional key-value context such as command, device ID, provider, model ID, or path

Examples:

- `android.adb.missing`: "ADB is not available." / "Install Android platform-tools and ensure `adb` is on PATH."
- `android.device.missing`: "No Android device was found." / "Start an emulator, connect a device, or pass `--device <serial>`."
- `ios.xcrun.missing`: "`xcrun` is not available." / "Install Xcode command line tools and run `xcode-select` if needed."
- `ios.simulator.multiple`: "Multiple booted iOS simulators were found." / "Pass `--device <udid>`."
- `provider.credential.missing`: "Provider credentials are missing." / "Set the provider environment variable or pass `--api-key`."

## Testing

Add unit tests at the narrowest useful level:

- `:verity:core` tests for report aggregation and rendering helpers.
- `:verity:device` tests for Android and iOS checkers with fake discovery and command runners.
- `:verity:cli` tests for provider/model/credential preflight using fake environment lookup.
- `:verity:mcp` tests proving `open_session` returns a structured preflight error and does not call the session factory when preflight fails.

The final implementation must run `./gradlew spotlessApply` and `./gradlew check`.

## Documentation

Update `docs/architecture.md` because this changes shared interfaces, CLI behavior, and MCP tool behavior. The update should explain which module owns each preflight responsibility and explicitly note that MCP does not validate LLM provider configuration.
