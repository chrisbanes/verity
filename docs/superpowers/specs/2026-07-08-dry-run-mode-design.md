# Dry-Run Mode Design

## Goal

Add `verity run --dry-run <journey-or-directory>` so journey authors can validate journey parsing, segmentation, fast-path classification, and generated slow-path Maestro YAML without opening a device session or sending device commands.

Dry-run mode may call the configured navigator LLM when slow-path action YAML is required. It must never run device preflight checks, create a `DeviceSession`, execute Maestro flows, inspect hierarchy, capture screenshots, or evaluate assertions.

## Public Behavior

`verity run --dry-run <path>` accepts the same journey path forms as normal `run`:

- a single `*.journey.yaml` file
- a directory containing `*.journey.yaml` files

Path resolution, sorted directory discovery, configured journey fallback, platform override, mixed-platform suite rejection, assertion strategy resolution, and journey parser failures should match normal `run` behavior.

Dry-run output is always printed to stdout. It is also written under the resolved output directory, using `--output-path`, `paths.output`, or the existing default `build/verity` in that precedence order.

## CLI Architecture

Add `--dry-run` to `RunCommand` and branch after config loading, output directory validation, path resolution, and journey resolution.

Normal execution remains unchanged. Dry-run execution uses a separate CLI-owned planner and renderer rather than a fake device session or a new mode inside `Orchestrator`. This gives a simple, testable guarantee that dry-run cannot accidentally connect to a device.

The dry-run path should still load project context with the same optional or required context behavior as normal run. It should run provider/model/API-key preflight only before the first slow-path YAML generation. Fast-path-only journeys should not require provider credentials. Dry-run must not run platform device preflight.

## Dry-Run Planner

Introduce a small dry-run model in `:verity:cli`, for example:

```kotlin
data class DryRunJourneyReport(
  val file: File,
  val journey: Journey,
  val launchYaml: String,
  val segments: List<DryRunSegmentReport>,
)

data class DryRunSegmentReport(
  val index: Int,
  val actions: List<DryRunActionReport>,
  val loop: DryRunLoopReport?,
  val assertion: DryRunAssertionReport?,
)
```

The exact type names can vary, but the model should represent the report explicitly rather than constructing console strings during planning.

For each journey, the planner:

1. Adds the launch flow as static YAML: `appId: <app>`, `---`, `- launchApp`.
2. Segments steps with `JourneySegmenter.segment`.
3. Classifies action groups with `InteractionMapper.forPlatform(platform)` or `Orchestrator.isFastPath`.
4. Represents fully mappable action groups as fast-path actions with their mapped interaction type or command description.
5. Calls `NavigatorAgent.generate(actions, app, platform, context)` for slow-path action groups and stores the generated YAML.
6. Represents loops with `action`, `until`, and `max`. If the loop action maps to a fast-path interaction, render that mapping. If it does not map, generate and include YAML for one loop action iteration.
7. Represents assertions by description and mode only. No assertion is evaluated during dry-run.

The planner should create `NavigatorAgent` lazily only when a slow-path action group or slow-path loop action needs YAML. Fast-path-only journeys should not invoke the navigator model.

## Output Format

The stdout report should be readable and stable enough for CLI tests. It should include:

- file path
- journey name
- app
- platform
- launch YAML
- segment index
- fast-path action descriptions
- slow-path generated YAML blocks
- loop action, condition, max iteration count, and generated loop YAML when relevant
- assertion description and assertion mode
- artifact path written for the journey

Artifact files should be Markdown under:

```text
<output-path>/dry-run/<journey-file-basename>.md
```

For a directory suite, each journey writes one artifact. If duplicate basenames are ever possible, the implementation should make names unique deterministically, but non-recursive directory discovery currently makes duplicate basenames impossible within one suite directory.

The artifact content can match stdout per journey. Keeping one rendering function for both stdout sections and files avoids drift.

## Error Handling

Invalid paths, empty directories, mixed-platform suites, invalid platform overrides, and parser failures should behave like normal `run`.

Missing required context should fail before any YAML generation.

Provider, API key, model, or navigator generation failures should fail the dry-run with context that identifies the file and segment being generated. A partial report should not be written as if successful.

Output directory creation and file writes are blocking I/O and should run on `Dispatchers.IO` when called from suspend code.

## Testing

Add CLI-level tests with injected dependencies so no real LLM or device is required.

Coverage should include:

- `run --dry-run <journey>` does not call the normal suite runner or any device-session boundary.
- invalid journey files still fail with useful parser errors.
- stdout includes launch YAML, segment indexes, generated slow-path YAML, assertion mode, and fast-path-only segment representation.
- dry-run writes per-journey Markdown artifacts under the resolved output directory.
- fast-path-only journeys do not invoke the navigator generator.
- directory input dry-runs journey files in sorted filename order and writes one artifact per journey.
- mixed-platform directory input is rejected before dry-run planning, matching normal run.

Use assertk assertions and `kotlinx-coroutines-test` where coroutine tests are needed.

## Documentation

Update `docs/architecture.md` to describe `verity run --dry-run` as a CLI mode that parses, segments, renders fast-path actions, and generates slow-path Maestro YAML without device access.

Update README or CLI help examples only if the implementation adds user-facing command examples elsewhere in the project.
