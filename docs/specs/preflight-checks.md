# Preflight checks

Preflight reports local environment problems before opening a device session or constructing an LLM client. This describes implemented behavior originating in the July 2026 design for [issue #45](https://github.com/chrisbanes/verity/issues/45).

## Report contract

Each issue carries a stable `code`, `severity`, human-readable `message`, suggested `remediation`, and optional string-valued `details`. A report preserves all issues and passes when it contains no errors. Warnings do not block execution.

Representative codes include `android.adb.missing`, `android.device.missing`, `ios.xcrun.missing`, `ios.simulator.multiple`, `provider.model.unknown`, `provider.credential.missing`, `provider.effort.unsupported`, and `path.not_writable`.

## Responsibility boundaries

| Layer | Checks |
| --- | --- |
| Core | Shared report types, readable paths, writable file targets, and runtime temp directory |
| Device | ADB availability and Android device state; Xcode tooling and booted iOS simulator selection |
| CLI run | Provider/model resolution, required credentials, journey input, project context, output, and selected device platform |
| MCP tools | Device preflight for `open_session`; writable target validation for screenshots saved to a file |

Android preflight accepts an ADB serial, checks its state when specified, and otherwise requires at least one available device. It rejects numeric IP-address device IDs. iOS preflight currently checks simulators: an explicit UDID must be booted; automatic selection requires exactly one booted simulator. These checks do not establish physical iOS device readiness.

## CLI behavior

Normal `run` resolves configuration and input, validates project context, and performs local preflight before creating the session and LLM client. Provider checks resolve supported names/model IDs, check credential presence, and validate any explicit reasoning effort against the selected model and backend. They do not contact providers to verify credentials, account entitlement, or remote availability.

Input/parser failures and setup/preflight failures have different [exit codes](run-artifacts.md#exit-codes-and-failure-boundaries). Expected preflight errors include their codes, messages, and remediation in CLI output.

### Reasoning-effort capability

Navigator and inspector effort resolve independently. An unset value uses the selected backend's normal defaults and needs no capability-table entry. An explicit value must match the provider, exact model ID, backend/auth path, and supported string shown below. Values are preserved exactly: comparison does not trim or change case, and values are not translated between providers. `none` is an explicit value where listed; it is distinct from omitting the setting.

| Provider and backend/auth | Exact model ID | Accepted explicit values |
| --- | --- | --- |
| OpenAI API key, Chat Completions or Responses endpoint selected for the model | `gpt-5` | `minimal`, `low`, `medium`, `high` |
| OpenAI API key, Chat Completions or Responses endpoint selected for the model | `gpt-5.1` | `none`, `low`, `medium`, `high` |
| OpenAI API key, Chat Completions or Responses endpoint selected for the model | `gpt-5.2` | `none`, `low`, `medium`, `high`, `xhigh` |
| OpenAI API key, Responses endpoint | `gpt-5-pro` | `high` |
| OpenAI API key, Responses endpoint | `gpt-5.2-pro` | `medium`, `high`, `xhigh` |
| Anthropic API key, Messages API | `claude-opus-4-5` | `low`, `medium`, `high` |
| Anthropic API key, Messages API | `claude-opus-4-6` | `low`, `medium`, `high`, `max` |
| Anthropic API key, Messages API | `claude-opus-4-7` | `low`, `medium`, `high`, `xhigh`, `max` |
| Anthropic API key, Messages API | `claude-sonnet-4-6` | `low`, `medium`, `high`, `max` |

For OpenAI, the CLI uses Chat Completions when the selected model supports that endpoint; otherwise it uses Responses when supported. Anthropic uses Messages. Other provider/model/backend combinations have no validated explicit-effort entry in this version. Their existing defaults remain usable when effort is unset.

The CLI passes an accepted string through the transport's native request field without translation: OpenAI Chat Completions uses `reasoning_effort`; OpenAI Responses uses `reasoning.effort`; Anthropic Messages uses `output_config.effort`. An API key does not authorize an unvalidated backend, and this table does not prove an account can access a model or that a remote request will succeed.

An unknown, blank, unsupported, or unvalidated explicit value reports `provider.effort.unsupported`. Normal run exits with setup code `3` before device preflight, session creation, LLM client creation, or a model request. This is a local configuration error, not a model failure.

[Dry run](dry-run.md) never performs device preflight. Provider, navigator-model, credential, and any explicit navigator-effort preflight is deferred until generated actions are needed; inspector-model and inspector-effort preflight are skipped.

## MCP behavior

`open_session` checks the selected device before opening a session. Expected preflight failures return `CallToolResult` with `isError = true` and a JSON text report containing the issues. Screenshot file-target failures use the same report shape. Other unexpected tool failures use the tool's generic error response.

MCP tools do not validate provider, model, or credential configuration. The CLI wrapper that starts MCP still resolves shared configuration; invalid provider/model settings can therefore prevent startup. This distinction preserves the [MCP device boundary](../adr/0001-mcp-device-boundary.md).

## Source and coverage

- [Preflight](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/Preflight.kt) and [PathPreflightChecker](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/PathPreflightChecker.kt): report and path contracts.
- [AndroidPreflightChecker](../../verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/AndroidPreflightChecker.kt) and [IosPreflightChecker](../../verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/IosPreflightChecker.kt): platform checks.
- [CliPreflightChecker](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/CliPreflightChecker.kt) and [VerityMcpServer](../../verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt): caller composition.
- [CliPreflightCheckerTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt) and [VerityMcpServerTest](../../verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt): fake checks, credential errors, and session rejection before connection.
- [ReasoningEffort](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ReasoningEffort.kt), [ReasoningEffortTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/ReasoningEffortTest.kt), and [VerityProviderTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityProviderTest.kt): exact model/backend capabilities and native fields.
