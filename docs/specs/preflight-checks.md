# Preflight checks

Preflight reports local environment problems before opening a device session or constructing an LLM client. This describes implemented behavior originating in the July 2026 design for [issue #45](https://github.com/chrisbanes/verity/issues/45).

## Report contract

Each issue carries a stable `code`, `severity`, human-readable `message`, suggested `remediation`, and optional string-valued `details`. A report preserves all issues and passes when it contains no errors. Warnings do not block execution.

Representative codes include `android.adb.missing`, `android.device.missing`, `ios.xcrun.missing`, `ios.simulator.multiple`, `provider.model.unknown`, `provider.credential.missing`, and `path.not_writable`.

## Responsibility boundaries

| Layer | Checks |
| --- | --- |
| Core | Shared report types, readable paths, writable file targets, and runtime temp directory |
| Device | ADB availability and Android device state; Xcode tooling and booted iOS simulator selection |
| CLI run | Provider/model resolution, required credentials, journey input, project context, output, and selected device platform |
| MCP tools | Device preflight for `open_session`; writable target validation for screenshots saved to a file |

Android preflight accepts an ADB serial, checks its state when specified, and otherwise requires at least one available device. It rejects numeric IP-address device IDs. iOS preflight currently checks simulators: an explicit UDID must be booted; automatic selection requires exactly one booted simulator. These checks do not establish physical iOS device readiness.

## CLI behavior

Normal `run` resolves configuration and input, validates project context, and performs local preflight before creating the session and LLM client. Provider checks resolve supported names/model IDs and check credential presence. They do not contact providers to verify credentials or remote availability.

Input/parser failures and setup/preflight failures have different [exit codes](run-artifacts.md#exit-codes-and-failure-boundaries). Expected preflight errors include their codes, messages, and remediation in CLI output.

[Dry run](dry-run.md) never performs device preflight. Provider, navigator-model, and credential preflight is deferred until generated actions are needed; inspector-model preflight is skipped.

## MCP behavior

`open_session` checks the selected device before opening a session. Expected preflight failures return `CallToolResult` with `isError = true` and a JSON text report containing the issues. Screenshot file-target failures use the same report shape. Other unexpected tool failures use the tool's generic error response.

MCP tools do not validate provider, model, or credential configuration. The CLI wrapper that starts MCP still resolves shared configuration; invalid provider/model settings can therefore prevent startup. This distinction preserves the [MCP device boundary](../adr/0001-mcp-device-boundary.md).

## Source and coverage

- [Preflight](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/Preflight.kt) and [PathPreflightChecker](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/PathPreflightChecker.kt): report and path contracts.
- [AndroidPreflightChecker](../../verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/AndroidPreflightChecker.kt) and [IosPreflightChecker](../../verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/IosPreflightChecker.kt): platform checks.
- [CliPreflightChecker](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/CliPreflightChecker.kt) and [VerityMcpServer](../../verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt): caller composition.
- [CliPreflightCheckerTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt) and [VerityMcpServerTest](../../verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt): fake checks, credential errors, and session rejection before connection.
