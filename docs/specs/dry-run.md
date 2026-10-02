# Dry run

`verity run --dry-run <path>` previews a journey file or directory suite without device access. This describes implemented behavior originating in the July 2026 dry-run design.

## Execution boundary

Dry run resolves and parses journeys, applies platform overrides and assertion strategy, and creates a report for each journey. Directory ordering, empty-directory rejection, and mixed-platform rejection follow the [suite spec](directory-suite-runs.md). Optional or required [project context](project-context.md) is loaded before planning.

It never performs device preflight, opens a device session, executes a flow, captures screen state, or evaluates an assertion. Assertion descriptions and modes in a report are planned checks, not verdicts.

The planner belongs to the CLI and uses segmentation and interaction mapping directly. It does not simulate a device session or invoke normal orchestration.

## Generated content

The report includes the static application-launch YAML and each segment's actions, loop, and assertion when present. Fully mappable action groups show their interaction descriptions. Other groups invoke the navigator to generate Maestro YAML. A loop shows its action, condition, and maximum repetitions; a mapped loop shows every interaction in body order, and a slow-path loop includes one generated YAML flow for the complete body. Execution and preview consume the same core-derived semicolon instructions. Conditions, including a `visually` prefix, are reported without capture or evaluation; see [loop conditions](loop-conditions.md).

Navigator creation and provider/navigator-model/credential preflight are deferred until generated YAML is needed. Fast-path-only suites require neither a valid provider configuration nor credentials. Inspector models are not validated because assertions are not evaluated. Slow-path planning may therefore make LLM calls and incur provider costs.

Generation failures identify the journey file and segment. All journeys are planned before report writing starts, so a generation failure does not produce a new partial report marked as successful.

## Reports and artifacts

The completed Markdown report is printed to stdout and written per journey under the resolved output root:

```text
<output-path>/dry-run/<journey-file-basename>.md
```

The root resolves from `--output-path`, `paths.output`, then `build/verity`. Reports include journey identity, platform, launch YAML, segment indexes, mapped interactions or generated YAML, loop details, assertion modes, and the artifact path. Filename collisions within one write receive numeric suffixes. The same renderer supplies file and console content.

Current input resolution also creates a timestamped directory under `<output-path>/runs/` before branching to dry run. Successful dry runs do not write normal journey-result JSON or a suite summary there; input/parser failures use that directory for a failure summary. Dry-run generation/context/report-write failures are handled by the preview path and do not have the normal run's complete [CI result contract](run-artifacts.md).

## Source and coverage

- [DryRunPlanner](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunPlanner.kt) and [DryRunPlannerTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/DryRunPlannerTest.kt): mapped actions, lazy generation, loops, and assertions.
- [DryRunRenderer](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunRenderer.kt) and [DryRunArtifactWriter](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/DryRunArtifactWriter.kt): Markdown and file naming.
- [RunCommandTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt): preview branching, sorted discovery, parsing, and deferred provider/model checks.
