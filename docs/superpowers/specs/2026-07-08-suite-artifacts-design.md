# Suite Artifacts And CI Results Design

## Context

GitHub issue 49 asks Verity to persist durable artifacts for single-journey and multi-journey runs. Today `verity run` prints pass/fail output and keeps only in-memory `JourneyResult` values. Generated Maestro YAML, visual assertion screenshots, hierarchy captures, per-journey outcomes, and suite summaries are not saved in a stable place for local debugging or CI collection.

Recent work already added directory suite runs, project output configuration, and shared preflight checks. `RunCommand` resolves `paths.output` / `--output-path` to `build/verity` by default and owns suite aggregation. `Orchestrator` owns segment execution and is the only layer that knows whether a segment used fast-path direct interactions, generated slow-path YAML, loop YAML, assertion modes, screenshots, hierarchy text, and reasoning.

## Goals

- Create a timestamped output directory for every `verity run` invocation.
- Write structured per-journey result files for both single-file and directory suite runs.
- Write a structured aggregate suite summary with pass/fail counts and result file references.
- Persist generated Maestro YAML for slow-path action segments and slow-path loop iterations using stable filenames.
- Represent fast-path direct interactions in results even when no YAML is generated.
- Reference generated flows, screenshots, hierarchy captures, and other evidence files from result JSON using stable relative paths.
- Return distinct CI-friendly exit codes for input/parser failures, setup failures, and journey failures.
- Document the artifact layout, result contract, and exit-code behavior in `docs/architecture.md`.

## Non-Goals

- Do not add an opt-out for artifact writing. Artifact persistence is the run contract and is always enabled for `verity run`.
- Do not persist the internal temporary files created by the Maestro device runner. Those files remain implementation details and are still cleaned up.
- Do not add a database, server-side artifact index, or retention policy.
- Do not change MCP behavior. This issue concerns CLI journey runs.
- Do not keep executing segments inside a journey after the first failed segment.

## Architecture

Add a small artifact subsystem without adding a new Gradle module.

`:verity:core` should define serializable result contract DTOs so `agent`, `cli`, and tests share one stable schema. DTO properties must use Kotlin camelCase with `@SerialName` only where the JSON wire format differs. Stable enum values should serialize as lowercase strings.

`:verity:agent` should enrich `JourneyResult` / `SegmentResult` or nearby result types with execution metadata collected while running segments:

- segment index and pass/fail status
- execution mode, such as `fast`, `slow`, `loop`, or `assertion-only`
- source action strings and assertion details
- assertion mode and reasoning
- generated-flow references
- evidence references
- structured error details for failed segment work

The agent should not decide the suite output root. Instead, `RunCommand` should pass an artifact recorder or per-journey artifact context into orchestration. This keeps CLI ownership of filesystem layout while allowing `Orchestrator` to write artifacts at the point it has the source data.

`:verity:cli` should own run directory creation, suite summary writing, per-journey result writing, and exit-code mapping. It already resolves `outputPath`, runs one or more journeys, and prints aggregate results.

## Artifact Layout

Every `verity run` invocation creates a run directory below the resolved output path:

```text
<outputPath>/
  runs/
    <yyyyMMdd-HHmmss>-<suite-slug>/
      summary.json
      journeys/
        001-login.json
        002-browse-home.json
      flows/
        001-login/
          segment-000-launch.yaml
          segment-002-actions.yaml
        002-browse-home/
          segment-001-loop-003.yaml
      evidence/
        001-login/
          segment-003-visual.png
          segment-004-tree.txt
```

The default root remains `build/verity`, so a normal run writes to `build/verity/runs/<timestamp>-<suite-slug>/...`.

Use these naming rules:

- Run directory: timestamp plus a slug from the single journey name or directory name.
- Journey artifacts: `<journey-index>-<journey-slug>`, where the index is one-based and zero-padded to three digits.
- Segment artifacts: `segment-<segment-index>` with a zero-padded three-digit segment index.
- Slow-path action YAML: `flows/<journey-key>/segment-<index>-actions.yaml`.
- Loop fallback YAML: `flows/<journey-key>/segment-<index>-loop-<iteration>.yaml`.
- Visual evidence: `evidence/<journey-key>/segment-<index>-visual.png`.
- Tree evidence: `evidence/<journey-key>/segment-<index>-tree.txt`.

All paths stored in JSON should be relative to the run directory, for example `flows/001-login/segment-002-actions.yaml`. Relative paths make artifacts movable and easy for CI systems to archive.

## Result Contract

Per-journey result JSON should include journey identity, source file, app, platform, pass/fail state, first failed segment, and segment details. A representative shape is:

```json
{
  "journey": {
    "name": "Login",
    "file": "journeys/login.journey.yaml",
    "app": "com.example.app",
    "platform": "android-tv"
  },
  "passed": false,
  "failedAt": 2,
  "segments": [
    {
      "index": 2,
      "passed": false,
      "executionMode": "slow",
      "actions": ["Navigate to Settings"],
      "assertion": {
        "description": "Settings is visible",
        "mode": "tree"
      },
      "reasoning": "Expected Settings but saw Home",
      "generatedFlows": ["flows/001-login/segment-002-actions.yaml"],
      "evidence": [
        {
          "type": "hierarchy",
          "path": "evidence/001-login/segment-002-tree.txt"
        }
      ],
      "error": {
        "kind": "journey_failure",
        "message": "Expected Settings but saw Home"
      }
    }
  ]
}
```

`summary.json` should include:

- run metadata: timestamp, output format version, input path, platform, provider/model IDs when available
- overall status: `passed` or `failed`
- counts: total, passed, failed
- journey result references with file paths and status
- top-level error details when parsing, setup, or preflight fails before any journey can run

Use stable lowercase wire values:

- status: `passed`, `failed`
- execution mode: `fast`, `slow`, `loop`, `assertion-only`
- assertion mode: existing assertion modes as lowercase strings
- evidence type: `flow`, `screenshot`, `hierarchy`
- error kind: `parser_failure`, `setup_failure`, `journey_failure`

## Execution Behavior

`RunCommand` should create the run directory before parsing and running journeys. This allows parser/input failures and setup/preflight failures to still produce a machine-readable `summary.json`.

Exit codes should be stable for CI:

| Exit code | Meaning |
|-----------|---------|
| `0` | All journeys passed. |
| `2` | Input or parser failure, including missing journey files, invalid journey YAML, mixed-platform directory suites, or invalid CLI/config values needed to resolve input. |
| `3` | Setup failure, including preflight, context validation, device setup, provider/model/credential setup, or LLM client construction failures. |
| `4` | One or more journeys ran and failed. |

Directory suite runs should continue to execute remaining journeys after one journey fails so `summary.json` can report complete pass/fail counts. A single journey should keep the current behavior of stopping at the first failed segment.

Generated Maestro YAML persistence should happen before execution so failed flow execution still has the exact YAML that was attempted. The internal temp YAML file used by `device/MaestroFlowRunner` should remain temporary and should still be deleted in its `finally` block. Persisted YAML artifacts are separate explicit copies written under the run artifact directory.

Fast-path interactions should not generate YAML files solely for persistence. They should be represented structurally in segment results using source action strings, execution mode `fast`, and any directly executed interaction details that are already available.

Visual assertions should save the screenshot file used for evaluation under `evidence/`. Tree assertions should save the rendered hierarchy text under `evidence/`. Visible and focused assertions do not write evidence files for this issue.

## Error Handling

If creating the run directory or writing required result files fails before a journey result is available, `verity run` should fail as setup failure with exit code `3` because the artifact contract cannot be satisfied. If a journey already has a pass/fail result and writing additional optional evidence fails, the journey result should include an artifact error and the run should continue when possible.

Suspend code that catches `Exception` must rethrow `CancellationException` first. Blocking filesystem operations such as creating directories and writing JSON should run on `Dispatchers.IO`.

## Testing

Add tests at the narrowest useful level:

- `:verity:core` tests for JSON serialization of result contracts, stable lowercase enum wire values, and nullable optional fields.
- `:verity:agent` tests with fake sessions for fast-path segment metadata, slow-path generated YAML references, loop fallback YAML references, tree evidence, visual screenshot evidence, and failed flow errors.
- `:verity:cli` tests with injected suite runner, fake clock, and temporary output directory for run directory names, per-journey JSON files, `summary.json`, relative artifact paths, and exit codes.
- Existing CLI suite aggregation tests should continue to pass after exit-code updates are reflected.

The final implementation must run `./gradlew spotlessApply` and `./gradlew check`.

## Documentation

Update `docs/architecture.md` because this changes CLI behavior, shared data models, and result contracts. The documentation should include:

- artifact directory layout
- per-journey result shape
- suite summary shape
- exit-code table
- ownership boundaries between `core`, `agent`, and `cli`
