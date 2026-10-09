# Run artifacts and CI results

Normal CLI runs always persist a suite summary and per-journey results. This describes implemented behavior originating in the July 2026 design for [issue #49](https://github.com/chrisbanes/verity/issues/49) and records the contract behind [ADR-0002](../adr/0002-required-run-artifacts.md).

## Layout

The output root resolves from `--output-path`, `paths.output`, then `build/verity`. Run directories use a UTC timestamp and a slug derived from the input path's basename, adding a numeric suffix on collision.

```text
<output-path>/runs/<yyyyMMdd-HHmmss>-<input-slug>/
  summary.json
  journeys/
    001-login.json
    002-browse-home.json
  flows/
    001-login/
      segment-002-actions.yaml
    002-browse-home/
      segment-001-loop-000.yaml
  evidence/
    001-login/
      segment-003-visual.png
      segment-004-tree.txt
```

Journey keys use a one-based, three-digit index and a slug of the journey name. Segment indexes and slow-path loop-iteration labels are zero-based and padded to three digits in filenames. Artifact references are relative to the run directory; journey identity separately records the input file path.

Generated slow-path actions and complete loop bodies are validated as `ActionFlow` lists. The selected object is rendered as YAML and saved before that same object is executed when possible. Fast-path interactions are represented by action text and execution mode without manufacturing generated YAML. The static application launch is executed separately and is not currently recorded as a generated segment flow.

Tree assertions and conditions save the hierarchy used for evaluation; visual assertions and conditions save the screenshot used for evaluation when possible. Loop evidence represents the final evaluated state, not a history of checks. Visible/focused assertions do not persist evidence files. Internal structured execution creates no Maestro flow temporary files. Public supplied-YAML execution retains its separate temporary-file lifecycle.

## JSON contract

`summary.json` uses `formatVersion: 1` and contains:

- `timestamp`, `inputPath`, and overall `status`.
- `total`, `passed`, and `failed` journey counts.
- `journeys`, each with `path`, `name`, and `status`.
- Optional `error` with `kind` and `message`.
- Optional `platform`, `provider`, `navigatorModel`, and `inspectorModel`.
- Optional `navigatorEffort` and `inspectorEffort`, each describing the requested setting with a `mode` and, for explicit settings, the exact `requested` string.

Effort metadata is suite-wide and is emitted when resolved run metadata is available. The wire modes are `explicit` and `backend-default`:

```json
{
  "navigatorEffort": { "mode": "explicit", "requested": "none" },
  "inspectorEffort": { "mode": "backend-default" }
}
```

`explicit` records what was requested, unchanged. `backend-default` records that no value was requested; it does not claim what effort the provider chose. No `actualEffort` or provider-default estimate is stored. Null-valued summary properties are omitted. Older format-version-1 summaries without these fields remain valid; absence means effort metadata was unavailable or the summary predates this extension. The extension keeps `formatVersion: 1`.

Each journey result contains `journey` identity (`name`, `file`, `app`, `platform`), `passed`, optional `failedAt`, `segments`, and an optional error. Each segment contains its `index`, `passed`, `executionMode`, source `actions`, optional assertion description/mode, `reasoning`, `generatedFlows`, `evidence`, and optional error. Evidence references contain `type` and `path`. Loop segments additionally contain optional `loop` metadata with `condition`, completed `iterations`, final `tier` and condition `reasoning`. An execution failure retains the previous evaluated condition metadata separately from the segment error. Non-loop segments omit `loop`. See [loop conditions](loop-conditions.md).

A journey result from a normal run also contains an additive `trail` object with the retained execution `entries`, `droppedEntries` and the `maxEntries`, `maxTextChars`, `maxFocusedNodes` and `maxInstructions` caps that bounded it. Each entry has `segment`, `granularity`, `origin`, optional `iteration`, `instructions`, `omittedInstructions`, `succeeded`, `truncated` and optional `focusBefore`/`focusAfter` lists of focused nodes (`path`, optional `resourceId`); an omitted list means focus was unknown and `[]` means none was observed. Results written for an execution exception omit `trail`. See [journey memory](journey-memory.md).

Wait segments add `wait` metadata (`condition`, `timeoutSeconds`, `elapsedMs`, completed `checks`, optional `tier`, and last completed condition `reasoning`). Their mode is `wait`; actions/generated flows are empty. Before any completed check, tier/evidence are absent. Nonwait segments omit `wait`. Each production wait check uses distinct evidence paths to preserve the last completed evaluation through a later incomplete poll. See [wait conditions](wait-conditions.md).

JSON property names use camelCase. Default values are included; null-valued properties are omitted. Stable wire values are:

| Field | Values |
| --- | --- |
| Status | `passed`, `failed` |
| Execution mode | `fast`, `slow`, `loop`, `wait`, `assertion-only` |
| Assertion mode | `visible`, `focused`, `tree`, `visual` |
| Evidence type | `flow`, `screenshot`, `hierarchy` |
| Error kind | `parser_failure`, `setup_failure`, `journey_failure`, `model_failure` |
| Loop/wait condition tier | `literal`, `focus`, `tree`, `visual` |
| Trail granularity | `interaction`, `flow` |
| Trail origin | `actions`, `loop`, `scroll-to-find` |
| Platform | `android-tv`, `android`, `ios` |
| Effort setting mode | `explicit`, `backend-default` |

## Exit codes and failure boundaries

| Code | Outcome | Summary error kind |
| --- | --- | --- |
| `0` | All journeys passed | Absent |
| `2` | Input resolution or journey parsing failed, including empty/mixed-platform directories | `parser_failure` |
| `3` | Configuration, output, preflight, required context, session/client setup, local flow validation, or required result writing failed | `setup_failure`, when a summary can be written |
| `4` | A journey returned a failed result or threw during execution, apart from a classified model or local validation failure | `journey_failure` |
| `5` | A navigator flow/scroll or inspector tree/visual request failed, timed out, was truncated or returned an empty/invalid response | `model_failure` |

Configuration loading/resolution and output validation happen before run-directory creation. Failures there, or failure to create the run directory itself, exit `3` without a summary. Once the directory exists, input failures produce a failed summary with zero executed journeys; setup failures attempt a setup summary. A failure to write the summary cannot itself guarantee another summary.

A failed journey result allows subsequent journeys to run. An execution exception aborts the suite and preserves completed results plus a failure result for the journey that threw. Navigator flow generation, scroll-direction and inspector tree/visual model failures abort the suite, preserve completed results and write `model_failure` for the affected journey and summary. No later journey executes. Valid negative inspector verdicts remain ordinary failed journey results with exit `4`; a valid navigator `NONE` remains an ordinary navigation result.

Locally authored invalid action lists and command preparation or runner initialisation failures use fixed safe setup diagnostics. They abort the suite with `setup_failure` for the affected journey and summary, retain completed results and exit `3`. Invalid generated model actions remain model failures (exit `5`). A required journey-result or summary-write failure takes precedence over an input, journey, model or local validation failure and exits `3`.

Generated-flow and evidence writes are optional. A failed optional write may omit its reference or use a temporary screenshot for evaluation; it does not independently fail the journey and does not guarantee an artifact diagnostic in the result. Assertions can still fail on their own verdict or execution error.

Each navigator and inspector request owns a 30-second timeout. Completion reasons `length`, `max_tokens` and `incomplete` are rejected case-insensitively before decoding; blank replies and invalid action JSON, directions or verdicts are model failures. Diagnostics contain fixed stage/failure-class text without raw replies, backend exception text, HTTP bodies/headers or raw causes. Authored model diagnostics redact API keys, bearer tokens and JWTs.

Caller cancellation and shorter enclosing deadlines propagate without producing completed failure results. An overall wait-deadline expiry remains a wait timeout; a model request that fails before that deadline follows the model-failure contract. The separate smart-planner policy in [issue #59](https://github.com/chrisbanes/verity/issues/59) retains its deterministic fallback. [Dry run](dry-run.md) uses the same navigator model policy through its separate report contract.

## Ownership and coverage

Core owns the serializable result schema, agent orchestration collects segment metadata and requests artifacts, and the CLI owns directory layout, JSON writing, aggregation, and exit codes.

- [RunResultContract](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/result/RunResultContract.kt) and [RunResultContractTest](../../verity/core/src/test/kotlin/me/chrisbanes/verity/core/result/RunResultContractTest.kt): schema and wire values.
- [Orchestrator](../../verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt): generated flows and assertion evidence.
- [RunArtifacts](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunArtifacts.kt): naming, relative paths, and result writing.
- [RunCommandTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt): JSON outcomes, exit codes, required-write failures, exception recovery, path containment, and directory collisions.
