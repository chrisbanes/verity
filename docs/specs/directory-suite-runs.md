# Directory suite runs

`verity run <path>` selects one journey file or a directory suite. This describes implemented behavior originating in the July 2026 design for [issue #48](https://github.com/chrisbanes/verity/issues/48).

## Input resolution

- The positional path takes precedence over `--journeys-path`, then `paths.journeys` in project configuration. Without any of these, input resolution fails.
- A file path resolves to one journey. Directory discovery includes only regular files whose names end in `.journey.yaml`, without descending into subdirectories.
- Directory journeys run in filename order. An empty directory is an input failure.
- All selected journeys are parsed before execution starts. Invalid input or parsing fails with exit code `2`; required artifact-write failures can take precedence with exit code `3`.
- A CLI or configured platform override applies to every journey. Without an override, each journey supplies its platform. A directory suite containing different resolved platforms is rejected before device setup.

The configured fallback can contain multiple journeys. The earlier configuration design's restriction to exactly one journey no longer applies.

## Execution and outcomes

Journeys execute sequentially with one shared device session and LLM executor. Each journey launches its application before running its segments. A failed segment stops that journey; a returned failed journey result does not stop the next journey.

An execution exception stops the suite. The failure artifacts retain completed journey results and identify the journey that threw; later journeys are not reported as executed.

Console output identifies each journey's file, name, application, and platform. Failed results include the failed segment and its reasoning. The suite summary reports total, passed, and failed counts. Any failed journey produces exit code `4`; an all-passed suite exits `0`.

Persisted results and setup failures follow the [run-artifacts contract](run-artifacts.md). [Dry run](dry-run.md) uses the same discovery and platform rules.

## Source and coverage

- [JourneyLoader](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/journey/JourneyLoader.kt): discovery and parsing.
- [RunJourneyResolver](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunJourneyResolver.kt): configured input fallback and platform override.
- [RunCommand](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt): suite resolution, execution, and reporting.
- [RunCommandTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt): ordering, empty input, mixed platforms, aggregate results, and exception artifacts.
- [RunJourneyResolverTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunJourneyResolverTest.kt): file and directory fallback.
