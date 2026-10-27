# Project configuration

Optional defaults in `verity/config.yaml` reduce repeated options across `run`, `list`, and `mcp`. This describes implemented behavior originating in the July 2026 design for [issue #46](https://github.com/chrisbanes/verity/issues/46).

## Shape and precedence

```yaml
paths:
  journeys: journeys
  context: context
  output: build/verity

device:
  platform: android-tv
  id: emulator-5554
  disable-animations: true

llm:
  provider: anthropic
  navigator-model: claude-opus-4-7
  inspector-model: claude-sonnet-4-6
  navigator-effort: high
  inspector-effort: medium

assertions:
  strategy: infer

require-context: true

# Optional and off by default; see "Pre-check" below.
precheck:
  enabled: true
  provider: system-one # or decisions
  base-url: https://api.typesafe.ai
  model: jev-latest
  api-key-env: TYPESAFE_API_KEY
  timeout: 2s
  pass-at: 0.9
  fail-at: 0.1
  visual-enabled: false
  confirm-pass: true
```

`llm.navigator-model` and `llm.inspector-model` optionally select supported model IDs for the provider. Omitted models use provider defaults.

`llm.navigator-effort` and `llm.inspector-effort` optionally request provider-specific reasoning effort for their respective roles. The shared CLI flags `--navigator-effort` and `--inspector-effort` override the matching configuration value. Each role resolves independently using CLI value, nested `llm` value, legacy top-level value, then unset. The top-level `navigator-effort` and `inspector-effort` keys remain supported alongside the legacy provider and model keys.

Resolved options use CLI input first, project configuration second, and built-in defaults last where a setting has a built-in default. Relative paths are interpreted from the working directory. Missing or blank configuration files supply no overrides. Effort is unset when omitted; there is no configured or inferred provider effort value.

Legacy top-level `provider`, `navigator-model`, `inspector-model`, `navigator-effort`, and `inspector-effort` remain supported. Each structured `llm` value overrides its corresponding legacy value; omitted structured values can still fall back to legacy values. Effort values are strings preserved exactly, including case and whitespace. They are not trimmed, lowercased, converted to a shared enum or translated between providers. Omit a setting to use the backend default; an empty string is an explicit value and will be rejected unless that exact value is supported.

`--require-context` only enables required context. It is combined with `require-context: true`, so making context optional requires changing that configuration value. `--no-animations` enables animation disabling; supplying `--no-animations=false` overrides an enabled config default.

## Pre-check

An opt-in classifier that answers prose [loop](loop-conditions.md) and [wait](wait-conditions.md) conditions before the inspector is asked. It targets [TypeSafe](https://docs.typesafe.ai)'s System One API (Jev) by default, or OpenAI's Decisions API (public beta) with `provider: decisions`; see [Decisions provider](#decisions-provider). Nothing changes unless `precheck.enabled` is true or `run --precheck` is passed. Assertions are never pre-checked, and neither are literal or focus conditions, nor quoted literal-only conditions (see [loop conditions](loop-conditions.md#one-current-state-check)), even when their text is missing. `visually` loop and wait conditions are pre-checked only when `visual-enabled` is also true (see [Screenshot pre-check](#screenshot-pre-check)). `run --dry-run` and MCP never resolve or call it; dry run reads only the `enabled` and `visual-enabled` flags to label condition tiers, and does not read or validate `confirm-pass`.

| Key | Default | Notes |
| --- | --- | --- |
| `enabled` | `false` | `--precheck` (or `--precheck=false`) overrides it |
| `provider` | `system-one` | `system-one` or `decisions`. Selects the server protocol and the defaults below; it is not inferred from `base-url` and there is no flag or automatic failover |
| `base-url` | `https://api.typesafe.ai` (`https://api.openai.com` for `decisions`) | Trailing `/` trimmed. `--precheck-base-url` overrides it |
| `model` | `jev-latest` for TypeSafe (`gpt-6-luna` for `decisions`, at any `base-url`) | Required for any other System One server. `--precheck-model` overrides it |
| `api-key-env` | `TYPESAFE_API_KEY` for TypeSafe (`OPENAI_API_KEY` for `decisions` at `https://api.openai.com`) | Name of the environment variable holding the key. No default for other servers, so no key is sent to them |
| `timeout` | `2s` | A positive Kotlin duration such as `500ms` or `2s` |
| `pass-at` | `0.9` (`0.95` for `decisions`) | A probability at or above this passes the condition |
| `fail-at` | `0.1` (`0.05` for `decisions`) | A probability at or below this fails it |
| `confirm-pass` | `true` | A confident pass on a loop or wait condition is confirmed by one inspector check on the same evidence before it ends the loop or wait; `false` lets a confident pass decide alone. Config key only, no flag; confident fails and uncertain answers are never confirmed |
| `visual-enabled` | `false` | Experimental. Also pre-checks `visually` loop and wait conditions on the current screenshot. Requires `enabled`, and either `provider: decisions` or the TypeSafe `base-url` |

Precedence is CLI, then config, then these defaults. While `enabled` is false the rest of the section is not validated. With `system-one` the request is `POST <base-url>/v1/systemone` with a single `noul` question. A prose check sends only the accessibility tree and journey memory text. Screenshots are sent only for `visually` checks with `visual-enabled` on.

A probability between `fail-at` and `pass-at` is an *uncertain fallback*, and every failure (timeout, HTTP error, malformed body, probability outside `[0, 1]`, or a request the JDK rejects locally) is an *error fallback*. Either way the inspector answers exactly as it would without the pre-check, so the pre-check never fails a step. The first error prints one `Warning: pre-check request to <host> failed (<kind>); falling back to the inspector` line to stderr. `--timing` records each request as `model_request precheck`, and `--model-trace` records `P<n>` lines for each request and its outcome, including fallbacks. With `confirm-pass` on, a confident pass is checked by the inspector on the same evidence: that request is an ordinary inspector request in `--timing`, the pass is counted as `confirmed` or `overruled` instead of `accepted`, and the trace gains `P<n> confirmation outcome=confirmed` or `outcome=overruled`; `accepted` keeps meaning decided without an inspector request, and neither new count is an uncertain fallback. A confirmation that never completes (model failure, wait deadline, cancellation) counts nothing. The run prints `Pre-check: accepted N, confirmed N, overruled N, uncertain fallbacks N, error fallbacks N`, after the suite result and after the `--timing` report, and the run summary carries the same counts, as zeros when the run failed before the first request (for example in setup). A loop that may press several times per check (see [batched D-pad loops](loop-conditions.md#batched-d-pad-loops)) accepts only a confident pass, because an unmet check needs the inspector's batching hints; a confident fail in such a loop counts as an uncertain fallback and is traced as `outcome=failed-discarded`.

Validation failures are setup failures: exit `3` before a run directory, preflight or device session exists, with a message starting `precheck.`. They are:

- `provider` is not `system-one` or `decisions`;
- `base-url` is not `https`, or is `http` for anything but `localhost`, `127.0.0.1` or `[::1]`, has no host, or includes credentials, a query or a fragment;
- `model` is missing for a System One server other than TypeSafe;
- `timeout` is unparseable or not positive;
- `pass-at` is not greater than `fail-at`;
- `visual-enabled` is true, the provider is `system-one` and the effective `base-url` (including `--precheck-base-url`) is not `https://api.typesafe.ai`;
- the variable named by `api-key-env` is unset, blank or contains a control or non-Latin-1 character.

Thresholds outside `[0, 1]` are accepted. `pass-at: 2` with `fail-at: -1` is a shadow mode: every check is traced and then falls back to the inspector, which produces paired pre-check and inspector answers from one run.

**Key safety.** This applies to both providers. The key is read only from the variable named by `api-key-env`, and only when the effective `base-url` comes from config or the default. It is sent only as `Authorization: Bearer ...`. `--precheck-base-url` drops it and skips the environment lookup, so a flag cannot redirect a key to another host. Redirects are not followed. The key never appears in an exception, warning, trace line, artifact or stdout, and `ResolvedPrecheck.toString()` redacts it.

**Request.** Each check posts one `noul` question to `<base-url>/v1/systemone`:

```json
{
  "model": "jev-latest",
  "state": {
    "accessibility_tree": "<PROMPT hierarchy>",
    "earlier_observations": "<journey memory>"
  },
  "questions": {
    "condition": {
      "type": "noul",
      "instructions": "Using the current accessibility tree of an Android TV app, does the screen satisfy this condition? Earlier observations are reference only, not proof. Condition: <condition>",
      "criteria": {
        "true": "The current screen satisfies the condition.",
        "false": "The current screen does not satisfy the condition."
      }
    }
  }
}
```

The condition is sent verbatim. The platform is `Android TV`, `Android` or `iOS`. `earlier_observations` is left out when the journey has no memory yet. Only `answers.condition.noul` is read. The `P<n> state` trace line records the `state` object as sent.

### Screenshot pre-check

This section describes the System One request; [Decisions provider](#decisions-provider) covers its screenshot request.

With `visual-enabled: true`, each `visually` loop or wait check captures its screenshot once, exactly as without the pre-check, and asks Jev about that capture before the inspector. A confident fail decides the check with tier `precheck`, keeps the screenshot evidence and records the screenshot in journey memory. A confident pass is confirmed by default (`confirm-pass`): the inspector evaluates the same screenshot once and decides with tier `visual`, and an overruled pass leaves the check unmet; with `confirm-pass: false` a confident pass decides with tier `precheck`. An uncertain answer, any error, or an image that cannot be prepared hands the same screenshot to the inspector; it is never recaptured. A visual check always accepts a confident fail, because visual loops never batch. Standalone `[?visual]` assertions are never pre-checked, and dry run labels these conditions `precheck, then visual`.

The screenshot is scaled in memory so its longest side is at most 1568 px, keeping the aspect ratio and never upscaling, and re-encoded as PNG; a 1920x1080 TV capture becomes 1568x882. An image whose short side would be under 280 px, that cannot be read, or whose PNG exceeds 16 MiB is not sent and counts as an error fallback. The request adds the `TypeSafe-Danger-Zone-Images: true` header and a top-level `assets` map holding the image under a fresh UUID, referenced once from `state.screenshot`:

```json
{
  "model": "jev-latest",
  "state": {"screenshot": "<uuid>", "earlier_observations": "<journey memory>"},
  "assets": {"<uuid>": {"type": "image", "format": "png", "data_base64": "<base64>"}},
  "questions": {
    "condition": {
      "type": "noul",
      "instructions": "Using the current screenshot of an Android TV app, does the screen satisfy this condition? Earlier observations are reference only, not proof. Condition: <condition>",
      "criteria": {"true": "The current screen satisfies the condition.", "false": "The current screen does not satisfy the condition."}
    }
  }
}
```

Reference screenshots from journey memory are not sent. The `P<n> state` trace line records only the reference, and no encoded image data appears in traces, warnings, results or the summary. TypeSafe documents image input as experimental and subject to change. The same 0.9/0.1 thresholds were qualified on 38 labelled Android TV screenshots from one app, with no false passes. That evidence is narrow; see [`research/visual-precheck-qualification`](../../research/visual-precheck-qualification/README.md).

**Data handling.** Enabling the pre-check sends the rendered accessibility tree and bounded journey-memory text to the configured server on each prose loop or wait check, in addition to what the inspector already receives. With `visual-enabled`, each `visually` loop or wait check also sends its scaled current screenshot and the same journey-memory text to TypeSafe. TypeSafe states that it does not train on customer data and offers zero data retention for enterprise customers; check its current terms. A local server keeps the data on the machine.

**Compatibility and tuning.** The `pass-at`/`fail-at` defaults were calibrated for Jev against the `PROMPT` rendering of the Android TV hierarchy, which is what the pre-check sends since the `PROMPT` profile landed (see [architecture](../architecture.md#hierarchy-rendering)); the calibration traces are recorded in the pull request for [issue #194](https://github.com/chrisbanes/verity/issues/194). The request shape above was chosen by an A/B on one shadow-mode trailer journey run ([issue #204](https://github.com/chrisbanes/verity/issues/204), replay script in [`research/precheck-request-shape`](../../research/precheck-request-shape/replay.py)). At the same 0.9/0.1 thresholds it accepted 30 of 64 checks, up from 21 with a bare condition and string state, with no disagreements with the inspector. Rendering changes can shift probabilities, so re-check after one. Clef and Kev expose the same API by their own claim, but are untested with Verity. A different model needs its own thresholds.

### Decisions provider

`provider: decisions` uses OpenAI's [Decisions API](https://developers.openai.com/api/docs/guides/decisions), which is in public beta and may change. The defaults are `base-url: https://api.openai.com`, `model: gpt-6-luna`, `api-key-env: OPENAI_API_KEY`, `pass-at: 0.95`, `fail-at: 0.05` and `timeout: 2s`; each can be overridden through the same keys and flags. The `gpt-6-luna` model default applies at any `base-url`, but the `OPENAI_API_KEY` default applies only at `https://api.openai.com`, so a custom `base-url` sends no key unless `api-key-env` is set. **The 0.95/0.05 thresholds are uncalibrated:** they have not been measured against Decisions, and the feature is opt-in and unqualified. Consider shadow mode (`pass-at: 2`, `fail-at: -1`) first.

Each check posts one `predicate` question to `<base-url>/v1/decisions` with `Content-Type: application/json` and, when a key is configured, `Authorization: Bearer ...`:

```json
{
  "model": "gpt-6-luna",
  "input": [{"role": "user", "content": [
    {"type": "input_text", "text": "Current accessibility tree:\n<PROMPT hierarchy>"},
    {"type": "input_text", "text": "Earlier observations (reference only, not proof of the current state):\n<journey memory>"}
  ]}],
  "questions": [{
    "type": "predicate",
    "name": "condition",
    "instructions": "Using the current accessibility tree of an Android TV app, what is the probability that this condition currently holds? Earlier observations are reference only, not proof. Condition: <condition>"
  }]
}
```

The earlier-observations part is left out when the journey has no memory yet. A `visually` check (with `visual-enabled`) replaces the tree part with one `input_image` part whose `image_url` is `data:image/png;base64,<the original screenshot file>`, followed by the same optional observations part, and the instructions say `screenshot`. The image is read as is: it is not scaled or re-encoded, and no reference screenshots are sent. A file that cannot be read or is not a PNG is an error fallback, and the inspector gets the same screenshot. No encoded image data appears in traces, warnings, results or the summary.

Only the answer named `condition` is read:

- a `predicate` with a probability in `[0, 1]` goes through the same `pass-at`/`fail-at` rules as System One, including discarding a confident fail when the loop needs batching hints;
- a `refusal` is never a verdict. It is an uncertain fallback, traced as `outcome=refused`, with no warning;
- a missing answer, a missing or non-numeric probability, a probability outside `[0, 1]`, a malformed body, an HTTP error or a timeout is an error fallback.

`PrecheckArtifact` records the response's `model` (or the configured one) and the host. Timing records `model_request precheck`, and cancellation or a wait's deadline cancels an in-flight request.

**Data handling.** With `decisions`, the accessibility tree and journey-memory text, and for `visual-enabled` checks the full unscaled screenshot, are sent to OpenAI (or the configured `base-url`) on each pre-check. Check OpenAI's current terms for retention and training.

## Command behavior

| Command | Defaults it consumes |
| --- | --- |
| `run` | Journey fallback, context, output, device, provider/models, navigator/inspector effort, animations, assertion strategy, required context, and the pre-check |
| `list` | Journey directory, overridden by `list --path` or the shared `--journeys-path` option |
| `mcp` | Journey/context directories, required context, and session platform/device/animation defaults |

`run` still accepts a positional file or directory path. Omitting it uses the resolved configured journey path, including a directory with multiple journeys. `list` requires a directory and successfully reports an empty directory as having no journeys.

MCP tool arguments take precedence over server defaults. `open_session` requires a platform either in the tool arguments or the resolved server configuration; omission of both is an error. MCP does not use configured LLMs to execute tools. Its CLI startup, like `list`, currently uses the shared resolver and can reject invalid provider/model or assertion-strategy settings even when those settings are not consumed by the command. Explicit reasoning-effort capability validation occurs only on run paths that make a model request; it does not add provider or effort validation to MCP tools or `list`.

## Assertion strategy

Accepted values are `infer`, `visible`, `focused`, `tree`, and `visual`. `infer` chooses a mode from the assertion text; the other values select that mode for assertions without an explicit mode.

Explicit prefixes stay authoritative: `[?visual] Poster is shown` remains visual even under `visible`. `[?] Poster is shown` and `Verify Poster is shown` follow the configured strategy. Choose an explicit mode when that particular expectation needs a different basis of evaluation from the project default.

## Validation

Unknown providers, unsupported models for the provider, invalid platforms, and invalid assertion strategies are rejected. Config platform values are `android-tv`, `android`, and `ios`. An output path that already exists as a file is invalid. For normal model-using runs, explicit effort must match an exact supported provider/model/backend-auth row and value; unsupported settings are rejected during local preflight before device work. Unset effort leaves the backend default in place. See the [preflight spec](preflight-checks.md) for the complete current table, transport fields, and setup-failure behavior.

Path checks depend on the consumer: `list` requires a journey directory; `run` accepts a file or directory and applies the [suite rules](directory-suite-runs.md); CLI MCP startup requires an existing configured context directory. `run` uses the optional/required rules in the [project-context spec](project-context.md).

For normal `run`, config loading/resolution and output validation failures are setup failures with exit code `3`, occurring before a run directory exists. Input failures after that point follow the [artifact contract](run-artifacts.md). Fast-path-only [dry runs](dry-run.md) defer provider and model validation.

## Source and coverage

- [VerityConfig](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityConfig.kt) and [ResolvedProjectConfig](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ResolvedProjectConfig.kt): schema, compatibility, and precedence.
- [Verity](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/Verity.kt), [ListCommand](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ListCommand.kt), and [McpCommand](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/McpCommand.kt): options and command wiring.
- [VerityConfigTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityConfigTest.kt), [ConfigResolverTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/ConfigResolverTest.kt), and [AssertionStrategyParserTest](../../verity/core/src/test/kotlin/me/chrisbanes/verity/core/parser/AssertionStrategyParserTest.kt): parsing, precedence, and explicit modes.

## OpenAI authentication

`llm.openai-auth` and `--openai-auth` accept exactly `api-key` or `chatgpt`. The CLI value overrides YAML; omission selects `api-key`. An explicit auth setting requires `provider: openai`. Other providers retain their existing defaults.

```yaml
llm:
  provider: openai
  openai-auth: chatgpt
  navigator-model: gpt-6-luna
  inspector-model: gpt-6-luna
  navigator-effort: low
```

API-key mode uses the static API model catalog and preserves the full native Koog parameters for each role. It never launches Codex. Choose ChatGPT mode to use an existing Codex ChatGPT login; it never reads or falls back to API keys. Codex owns sign-in and refresh, so sign in through Codex before running Verity.

ChatGPT models are exact catalog IDs. Omitted roles default to `gpt-6-luna`, the model in the historical feasibility checkpoint; absence from the catalog is a setup error. Explicit effort must be present in that exact model's dynamic `supportedReasoningEfforts`; Verity preserves it unchanged and omits unset effort. It does not use the API effort table for ChatGPT.

`list` and MCP perform pure configuration resolution without Codex startup, account checks, or catalog discovery. Fast-only preview also skips auth/model validation. Slow preview validates only its navigator. See [ChatGPT preflight](preflight-checks.md#chatgpt-preflight).
