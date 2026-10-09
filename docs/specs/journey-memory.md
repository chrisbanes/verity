# Journey memory

CLI journeys give the inspector bounded context from earlier in the same journey, and save the execution trail with the run artifacts. This describes the implemented behavior from [issue #90](https://github.com/chrisbanes/verity/issues/90). `JourneyMemory` is in `verity/agent`; the artifact types are in `verity/core`.

## What is remembered

| Memory | Content | Recorded when |
| --- | --- | --- |
| Verdicts | Subject, passed or failed, reasoning | An assertion (all four modes), a loop (its `until` condition and final evaluation) or a wait with a completed check finishes. Individual loop and wait polls are not verdicts. |
| Execution trail | One entry per execution (below) | After each execution attempt |
| Earlier screenshots | A copy of the first and of the most recent screenshot given to a visual inspection | After a visual inspection returns a verdict |

Only parsed inspector or deterministic verdicts are remembered. A model failure (exit `5`) is never a verdict and keeps its existing abort path. An attempt that is cancelled, or fails with anything other than an interaction failure (for example a navigator model failure before a flow runs), records no trail entry.

## Execution trail

Each fast-path interaction and each slow-path flow gets exactly one entry:

- `granularity` is `interaction` for one mapped interaction, or `flow` for a slow-path generated flow. A flow is a single device call, so its focus is observed only before and after the whole call. Intermediate focus between its internal commands is not observed, and flows are never split to imply otherwise.
- `origin` is `actions` for a segment's own actions, `loop` for a loop body (with a zero-based `iteration`), or `scroll-to-find` for an automatic scroll that looks for an off-screen target (it carries the target's instruction).
- `instructions` are the source instruction text. `succeeded` is false for an unsuccessful flow result or an interaction failure; the after-focus is still observed.
- `focusBefore` and `focusAfter` come from the focus helper of [focus-change waiting](focus-change-waiting.md) (`FocusChangeObserver.capture`, 2 seconds each). An omitted value means unknown (the capture timed out, failed, or the session does not support bounded capture). An empty list means focus was observed as absent. Focus capture is advisory: it never fails, retries or reorders the journey, and does not wait for a focus change. When a capture hangs it adds up to about 4 seconds to that execution. Caller cancellation still propagates.

The launch of the app is not recorded.

## Caps and truncation

| Cap | Value | Policy |
| --- | --- | --- |
| Trail entries | 20 | The most recent are retained; `droppedEntries` counts older ones |
| Verdicts | 10 | The most recent are retained; the prompt notes how many older ones were omitted |
| Instructions per entry | 10 | The first ten are kept in order; `omittedInstructions` counts the rest and the prompt notes them |
| Text per field | 300 characters | An instruction, assertion, reasoning or resource ID is cut to 300 characters including a trailing `…` |
| Focused nodes per observation | 5 | The first five in tree order are kept |

A cut to an entry's instructions, text or focus sets that entry's `truncated` flag. Memory never grows beyond these caps.

## What the inspector receives

Immediately before each tree or visual assertion, and each loop condition check, memory is rendered into the inspection's `InspectionContext`. A wait builds its context once, when the wait starts, and freezes it: its earlier screenshots are private copies, so every poll sees the references from the start of the wait even though polls keep recording newer screenshots (the copies are removed when the wait ends). The text lists the verdicts, then the trail with focus before and after (`unknown` or `none` where applicable), and states that the memory is a reference and not proof that the current assertion or condition passes. The inspector prepends its existing "Reference context (earlier observations, not proof of the current condition)" header.

Visual inspections (including conditions beginning `visually`) also receive at most two earlier screenshots. The first earlier screenshot is Reference screenshot 1 and the most recent is Reference screenshot 2. If only one earlier screenshot exists it is attached once. Both are distinct from the current screenshot, which keeps its own label. Tree inspections receive text only. A fresh journey sends no reference context.

Screenshots are copied into a journey-owned temporary directory when the inspection finishes, because artifact paths are reused and temporary screenshots are deleted. That directory holds at most two files (plus, during a visual wait, a `frozen-*` subdirectory of up to two copies), the latest is replaced atomically, a failed copy keeps the previous reference, and the directory is removed when the journey ends. These copies are not saved as run artifacts, and neither are the retained verdicts.

## Scope

Memory belongs to one `Orchestrator.run` call. A suite, or a reused `Orchestrator`, starts every journey with empty memory. Dry run does not execute, and MCP tools do not run the `Orchestrator`, so neither uses memory.

## Artifact

Every journey result written by a normal run includes an additive `trail` object with `entries` (oldest first), `droppedEntries`, `maxEntries`, `maxTextChars`, `maxFocusedNodes` and `maxInstructions`. A failure written for an exception has no `trail`. See [run artifacts](run-artifacts.md); `formatVersion` remains 1.

## Qualification

`JourneyMemoryTest` and `OrchestratorJourneyMemoryTest` cover caps, truncation, focus mapping, both execution paths, loops, scroll-to-find, prompt delivery, screenshot selection and deduplication, and isolation between journeys using fake sessions and inspectors. The Android and iOS settings smoke tests assert that a real journey records interaction entries; they do not assert focus values.
