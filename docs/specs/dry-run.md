# Dry run

`verity run --dry-run <path>` previews a journey file or directory suite without device access. This describes implemented behavior originating in the July 2026 dry-run design.

## Execution boundary

Dry run resolves and parses journeys, applies platform overrides and assertion strategy, and creates a report for each journey. Directory ordering, empty-directory rejection, and mixed-platform rejection follow the [suite spec](directory-suite-runs.md). Optional or required [project context](project-context.md) is loaded before planning.

It never performs device preflight, opens a device session, executes a flow, captures screen state, or evaluates an assertion. Assertion descriptions and modes in a report are planned checks, not verdicts.

The planner belongs to the CLI and uses segmentation and interaction mapping directly. It does not simulate a device session or invoke normal orchestration.

## Generated content

The report includes the static application-launch YAML and each segment's actions, loop, and assertion when present. Fully mappable action groups show their interaction descriptions. Other groups invoke the navigator to generate validated structured actions, then render that selected list as Maestro YAML. A loop shows its action, condition, and maximum repetitions; a mapped loop shows every interaction in body order, and a slow-path loop includes one generated YAML flow for the complete body. Execution and preview consume the same core-derived semicolon instructions. Conditions, including a `visually` prefix, are reported without capture or evaluation; see [loop conditions](loop-conditions.md).

Navigator creation and provider/navigator-model/credential preflight are deferred until generated actions are needed. Fast-path-only suites require neither a valid provider configuration nor credentials, and explicit navigator effort is not validated. Inspector models and inspector effort are not validated because assertions are not evaluated. When generation is needed, only the navigator's explicit effort is checked against the selected provider/model/backend before client creation, then passed through unchanged. An unsupported setting exits with setup code `3`; a fast-only preview remains lazy even when the unused effort settings are invalid. Slow-path planning may make LLM calls and incur provider costs. See the [preflight spec](preflight-checks.md#reasoning-effort-capability) for supported rows and request fields.

Mapped groups and generated complete action lists pass the device-free command compiler before entering the report. Generated lists are decoded from strict JSON and rendered as YAML only for the preview. Validation performs no filesystem or device operations.

Generation failures identify the journey file, segment and fixed model stage/failure class. All journeys are planned before report writing starts, so a generation failure stops later planning and does not produce a new partial report marked as successful.

## Reports and artifacts

The completed Markdown report is printed to stdout and written per journey under the resolved output root:

```text
<output-path>/dry-run/<journey-file-basename>.md
```

The root resolves from `--output-path`, `paths.output`, then `build/verity`. Reports include journey identity, platform, launch YAML, segment indexes, mapped interactions or generated YAML, loop details, assertion modes, and the artifact path. Filename collisions within one write receive numeric suffixes. The same renderer supplies file and console content.

Current input resolution also creates a timestamped directory under `<output-path>/runs/` before branching to dry run. Successful dry runs do not write normal journey-result JSON or a suite summary there; input/parser failures use that directory for a failure summary. Dry-run generation/context/report-write failures are handled by the preview path and do not have the normal run's complete [CI result contract](run-artifacts.md).

The parser-failure summary may carry the raw navigator and inspector effort requests resolved from CLI or configuration. A successful preview still writes only its Markdown report; it does not add the normal suite summary or journey-result JSON.

## Exit codes and failure boundaries

| Code | Outcome |
| --- | --- |
| `0` | The entire suite was planned and required reports were written |
| `2` | Input resolution or journey parsing failed |
| `3` | Configuration/provider/model/context setup, local flow validation or required Markdown writing failed |
| `5` | Slow-path navigator action or complete-loop generation had a model failure |

Navigator generation shares the execution request policy: a request-owned 30-second timeout, failed request, known case-insensitive truncation reasons (`length`, `max_tokens`, `incomplete`), empty text and invalid action JSON all produce a model failure. Metadata is checked before parsing. Raw model replies, backend exception text, HTTP bodies/headers and raw causes never enter the error report; fixed diagnostics and redacted authored text retain journey/segment context.

Provider/navigator creation occurs outside the generation failure boundary. Invalid provider/model configuration, required context, temporary-file I/O, missing referenced resources, cleanup and ambiguous SDK validation faults remain setup failures with exit `3`. Required Markdown writing also exits `3`. These preview failures do not create normal journey-result JSON or a suite summary.

Caller cancellation and shorter enclosing timeouts propagate without a completed success or failure report. An enclosing overall wait deadline owns its timeout; it is separate from an individual model request. The smart-planner fallback specified by [issue #59](https://github.com/chrisbanes/verity/issues/59) retains its own deterministic recovery policy.

## Source and coverage

- [DryRunPlanner](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunPlanner.kt) and [DryRunPlannerTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunPlannerTest.kt): mapped actions, lazy generation, loops, and assertions.
- [DryRunRenderer](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunRenderer.kt) and [DryRunArtifactWriter](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunArtifactWriter.kt): Markdown and file naming.
- [RunCommandTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt): preview branching, sorted discovery, parsing, deferred provider/model checks, model/setup exit codes, report-write precedence and cancellation.
