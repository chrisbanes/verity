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

Android preflight accepts an ADB serial, checks its state when specified, and otherwise requires at least one available device. It rejects numeric IP-address device IDs. iOS preflight currently checks simulators: an explicit UDID must be booted; automatic selection requires exactly one booted simulator. These checks do not establish physical iOS device readiness. Device commands run as child processes, and cancelling a device check force-stops its running `adb` or `xcrun` command.

A passing iOS preflight reports the simulator it resolved on `PreflightReport.deviceId`: the explicit UDID echoed back, or the single booted simulator for automatic selection. Failed reports and Android reports leave it unset. Session creation uses that UDID instead of listing simulators again.

## CLI behavior

Normal `run` resolves configuration and input, validates project context, and performs local preflight before creating the session and LLM client. Provider checks resolve supported names/model IDs, check credential presence, and validate any explicit reasoning effort against the selected model and backend. API-key checks do not contact providers to verify credentials, account entitlement, or remote availability. ChatGPT mode performs the backend-specific preflight below.

Input/parser failures and setup/preflight failures have different [exit codes](run-artifacts.md#exit-codes-and-failure-boundaries). Expected preflight errors include their codes, messages, and remediation in CLI output.

### Model trace

`run --model-trace <file>` opens the trace during local path checks, so Codex preparation and role validation are traced too. Verity appends to an existing regular file and creates a new file with owner-only `0600` permissions. It refuses symlinks, directories and other non-regular files. A path that can't be opened safely reports `path.not_writable` with a fixed message and exits `3` before Codex preparation and session creation. Without the option, nothing is opened or written.

Each line is `<UTC timestamp> <source> <text>`. The source is `request`, `codex.sent`, `codex.received` or `codex.stderr`. Newlines, carriage returns and backslashes in the text are escaped, so each event stays on one line. Lines are capped at 16 KiB and end with `…[truncated N bytes]` when cut. There is no total size cap. Request entries record the prompt name, model, system and user text, each image's label and path (never image bytes), then the reply text, finish reason and duration, or the failure's class, backend failure kind, duration and message passed through the diagnostic redactor. A cancelled or timed-out request has a start entry but no reply or failure entry. If a write fails, tracing stops for the rest of the run and the run continues.

The trace contains prompts, hierarchy dumps, image paths and model replies. Treat it as sensitive.

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

`open_session` checks the selected device before opening a session. Expected preflight failures return `CallToolResult` with `isError = true` and a JSON text report containing the issues. Screenshot file-target failures use the same report shape. Other unexpected tool failures use the tool's generic error response. A passing iOS preflight's resolved UDID is used to open the session, and the response still reports `device: auto-discovered` when no device was supplied.

MCP tools do not validate provider, model, or credential configuration. The CLI wrapper that starts MCP still resolves shared configuration; invalid provider/model settings can therefore prevent startup. This distinction preserves the [MCP device boundary](../adr/0001-mcp-device-boundary.md).

## Source and coverage

- [Preflight](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/Preflight.kt) and [PathPreflightChecker](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/preflight/PathPreflightChecker.kt): report and path contracts.
- [AndroidPreflightChecker](../../verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/AndroidPreflightChecker.kt) and [IosPreflightChecker](../../verity/device/src/main/kotlin/me/chrisbanes/verity/device/preflight/IosPreflightChecker.kt): platform checks.
- [CliPreflightChecker](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/CliPreflightChecker.kt) and [VerityMcpServer](../../verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt): caller composition.
- [CliPreflightCheckerTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/CliPreflightCheckerTest.kt) and [VerityMcpServerTest](../../verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt): fake checks, credential errors, and session rejection before connection.
- [ReasoningEffort](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ReasoningEffort.kt), [ReasoningEffortTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/ReasoningEffortTest.kt), and [VerityProviderTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityProviderTest.kt): exact model/backend capabilities and native fields.

## ChatGPT preflight

ChatGPT mode checks readable local paths first, then prepares the isolated backend before session creation. The read-only device readiness check runs concurrently with backend preparation; its result is reported only when the Codex checks pass, and it is cancelled on Codex failure, startup timeout, or caller cancellation, which stops any running device command. It reads the account with `refreshToken: false`, requires a ChatGPT account, and enumerates the dynamic model catalog with bounded pagination. The navigator requires verified text input; the inspector requires text and image input. Each role's explicit effort is checked independently. No-turn ephemeral role-validation threads verify exact model, provider, and isolation without inference or silent fallback. Each one must report `instructionSources: []`; a loaded instruction file, such as a global `AGENTS.md`, fails `codex.isolation`. Effective configuration must report Codex's default ChatGPT request origins; a `chatgpt_base_url` or `openai_base_url` that cannot be pinned fails `codex.isolation` without reporting device results. Catalog visibility does not guarantee continuing entitlement; inference failures follow the model-failure contract, so an inference-time Codex protocol break is a model failure reported as `backend protocol failed`.

Safe diagnostics use `codex.installation`, `codex.version`, `codex.host`, `codex.auth`, `codex.model`, `codex.modality`, `codex.effort`, `codex.protocol`, `codex.isolation`, `codex.startup_timeout`, or `codex.cleanup`. Invalid local auth configuration uses `codex.auth.invalid`. Codex runs from an owned home that links only `auth.json`, so ChatGPT mode requires the default file credential store. Credentials held in the keyring, by the keyring store or the auto store, fail `codex.auth`, and its remediation names `cli_auth_credentials_store = "file"`. Messages and remediation are fixed; raw account, configuration, stderr, RPC failures and causes are excluded. Failed preparation or failed device checks close any acquired backend. These are setup failures, exit `3`, before session creation.

The conservative minimum is Codex CLI **0.159.0** on macOS, with required experimental raw events and schema/effective-policy checks. Effective policy is read with `includeLayers`. The `tools.update_plan.enabled` and `tools.experimental_request_user_input.enabled` keys, which the typed configuration omits, must be `false` in the winning layer, so a managed layer that enables either fails `codex.isolation`.

Evidence by version and host:
- **codex-cli 0.161.0** is runtime-qualified on **macOS 26.7 arm64**. Verity's own backend passed the opt-in `codexQualification` task (preparation, role validation, navigator text, inspector screenshot, cleanup) with **gpt-6-luna / low** on 2026-10-09.
- **0.159.0** is source-verified against rust-v0.159.0 ([evidence](https://github.com/chrisbanes/verity/issues/92#issuecomment-6085385043)), not runtime-qualified. The [historical checkpoint](https://github.com/chrisbanes/verity/issues/92#issuecomment-5955708208) ran a 0.159.0 binary with a probe client, not Verity's backend.
- Linux, Windows, other hosts and other versions are not qualified; newer versions remain gated by runtime checks.

Safe diagnostics never include raw frames or stderr. With [`--model-trace`](#model-trace), the trace file records every app-server frame sent and received, in the order the client handles them, plus stderr lines as they arrive. That file is the only place they are retained.

Offline fake-child tests cover client behavior and owned cleanup; they are not real inference or entitlement evidence.

See the [official app-server documentation](https://learn.chatgpt.com/docs/app-server) and [configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference) for the upstream interfaces. Verity's required isolation is version-specific and fails closed when it cannot verify the contract.
