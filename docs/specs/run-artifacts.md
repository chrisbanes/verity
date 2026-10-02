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

Generated slow-path action and loop YAML is saved before flow execution when possible. Fast-path interactions are represented by action text and execution mode without manufacturing generated YAML. The static application launch is executed separately and is not currently recorded as a generated segment flow.

Tree assertions save the hierarchy used for evaluation; visual assertions save the screenshot used for evaluation when possible. Visible/focused assertions do not persist evidence files. Maestro runner temporary files remain separate from these durable artifacts.

## JSON contract

`summary.json` uses `formatVersion: 1` and contains:

- `timestamp`, `inputPath`, and overall `status`.
- `total`, `passed`, and `failed` journey counts.
- `journeys`, each with `path`, `name`, and `status`.
- Optional `error` with `kind` and `message`.
- Optional `platform`, `provider`, `navigatorModel`, and `inspectorModel`.

Each journey result contains `journey` identity (`name`, `file`, `app`, `platform`), `passed`, optional `failedAt`, `segments`, and an optional error. Each segment contains its `index`, `passed`, `executionMode`, source `actions`, optional assertion description/mode, `reasoning`, `generatedFlows`, `evidence`, and optional error. Evidence references contain `type` and `path`.

JSON property names use camelCase. Default values are included; null-valued properties are omitted. Stable wire values are:

| Field | Values |
| --- | --- |
| Status | `passed`, `failed` |
| Execution mode | `fast`, `slow`, `loop`, `assertion-only` |
| Assertion mode | `visible`, `focused`, `tree`, `visual` |
| Evidence type | `flow`, `screenshot`, `hierarchy` |
| Error kind | `parser_failure`, `setup_failure`, `journey_failure` |
| Platform | `android-tv`, `android`, `ios` |

## Exit codes and failure boundaries

| Code | Outcome | Summary error kind |
| --- | --- | --- |
| `0` | All journeys passed | Absent |
| `2` | Input resolution or journey parsing failed, including empty/mixed-platform directories | `parser_failure` |
| `3` | Configuration, output, preflight, required context, session/client setup, or required result writing failed | `setup_failure`, when a summary can be written |
| `4` | A journey returned a failed result or threw during execution | `journey_failure` |

Configuration loading/resolution and output validation happen before run-directory creation. Failures there, or failure to create the run directory itself, exit `3` without a summary. Once the directory exists, input failures produce a failed summary with zero executed journeys; setup failures attempt a setup summary. A failure to write the summary cannot itself guarantee another summary.

A failed journey result allows subsequent journeys to run. An execution exception aborts the suite and preserves completed results plus a failure result for the journey that threw. A required result-write failure takes precedence over a journey/input failure and exits `3`.

Generated-flow and evidence writes are optional. A failed optional write may omit its reference or use a temporary screenshot for evaluation; it does not independently fail the journey and does not guarantee an artifact diagnostic in the result. Assertions can still fail on their own verdict or execution error.

Cancellation propagates rather than being converted into a completed run failure. [Dry run](dry-run.md) has a separate report contract.

## Ownership and coverage

Core owns the serializable result schema, agent orchestration collects segment metadata and requests artifacts, and the CLI owns directory layout, JSON writing, aggregation, and exit codes.

- [RunResultContract](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/result/RunResultContract.kt) and [RunResultContractTest](../../verity/core/src/test/kotlin/me/chrisbanes/verity/core/result/RunResultContractTest.kt): schema and wire values.
- [Orchestrator](../../verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt): generated flows and assertion evidence.
- [RunArtifacts](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunArtifacts.kt): naming, relative paths, and result writing.
- [RunCommandTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt): JSON outcomes, exit codes, required-write failures, exception recovery, path containment, and directory collisions.
