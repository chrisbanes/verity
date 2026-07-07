# Directory Suite Runs Design

## Goal

Support `verity run <directory>` by resolving all matching journey files in deterministic filename order and executing them as a console-oriented suite.

This change is limited to input resolution, ordered execution, failure reporting, and CLI-level coverage for GitHub issue #48. Artifact directories, persisted Maestro flows, and structured JSON result files are intentionally out of scope and belong to #49.

## Public Behavior

`verity run <path>` accepts either:

- a single `*.journey.yaml` file, preserving current behavior
- a directory containing `*.journey.yaml` files

For directory input, journey files are discovered non-recursively with `JourneyLoader.listJourneyFiles(directory)`, which already filters to regular files ending in `.journey.yaml` and sorts by filename. Empty directories produce a clear `CliktError` and a non-zero exit code.

Directory suites continue after an individual journey fails. The final process outcome is failed if any journey failed.

## CLI Architecture

Add a small CLI-owned resolved input model, for example:

```kotlin
data class ResolvedJourney(
  val file: File,
  val journey: Journey,
)
```

`RunCommand` should split into three responsibilities:

1. Resolve the input path into an ordered `List<ResolvedJourney>`.
2. Build shared runtime dependencies once: provider, models, device session, prompt executor, context, and orchestrator factories.
3. Execute the ordered journeys and print per-journey plus aggregate console output.

The runner should be shaped around an ordered list of resolved journeys so #49 can later attach artifact and result writing without changing directory discovery or command resolution.

## Execution Flow

Single-file input resolves to one `ResolvedJourney` and runs exactly as today.

Directory input resolves to all matching journeys, then runs each journey sequentially using the same device session and LLM executor. Each journey is still passed to `Orchestrator.run(journey)`, so segment execution and stop-on-failed-segment behavior remain unchanged inside a single journey.

The suite-level loop continues to the next journey after a failed journey and records the result for the final summary.

## Console Output

Each journey should print enough context to identify what is running:

- source file path or filename
- journey name
- app
- platform

On failure, output must include:

- source file
- journey name
- failed segment index
- failed segment reasoning

At the end, print an aggregate summary with total, passed, and failed counts. If any journey failed, throw a `CliktError` after printing the summary so the command exits non-zero.

## Error Handling

Missing input remains a usage error.

Missing paths and unsupported paths produce clear `CliktError` messages. A file path should still be expected to be a journey file. A directory with no matching journey files should fail with a clear message such as:

```text
No journey files found in: <directory>
```

Journey parsing errors can continue to surface from `JourneyLoader.fromFile(file)` and should naturally include the file being resolved by wrapping only if the current message lacks enough path context.

## Testing

Add CLI-level tests around command behavior rather than only loader or orchestrator behavior. To avoid real devices and LLMs, make `RunCommand` accept injected functions for resolving or executing journeys, with production defaults.

Coverage should include:

- single-file input still resolves and runs one journey
- directory input runs files in sorted filename order
- empty directory returns a clear non-zero error
- failing journey output includes file, journey name, and failed segment
- multiple journeys aggregate the final pass/fail outcome

Keep assertions in assertk, matching existing project test style.
