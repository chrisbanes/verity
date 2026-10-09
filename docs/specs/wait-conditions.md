# Wait conditions

Use a wait when the app is still changing and the journey should continue as soon as the current state meets a condition. A wait performs no device action and is a standalone segment in normal CLI execution.

```yaml
steps:
  - Tap Settings
  - "Wait until Settings"
  - "Wait until Account is focused up to 3 seconds"
  - "Wait until visually the loading spinner is gone up to 10 seconds."
  - "[?tree] The account page contains a profile section"
```

## Syntax and evaluation

`Wait until <condition>` is case-insensitive and defaults to 20 seconds. An optional trailing `up to N seconds` (or singular `second`) supplies a positive whole number of seconds; a final period is optional. Conditions may span lines within a YAML scalar. Blank conditions (including a bare `visually` prefix), zero, negative, fractional, overflowing or malformed duration limits are rejected during parsing. Assertion prefixes retain priority, so `[?tree] Wait until Ready` is an assertion. Saved journeys retain scalar strings; the canonical serializer omits the default limit when the condition round-trips unchanged, and otherwise writes the explicit limit to preserve its meaning.

A check uses the same tiers as [loop conditions](loop-conditions.md#one-current-state-check):

| Condition | Evaluation | Use when |
| --- | --- | --- |
| Complete literal text | Case-insensitive substring search in the current hierarchy | Visible text establishes the condition without a model call |
| `<target> is focused`, `<target> has focus`, `focus is on <target>` | Deterministic focus relationship | Navigation focus establishes the condition; false focus does not call the inspector |
| Other prose | Inspector verdict over the current CONTENT hierarchy | The condition describes relationships in accessibility state |
| Leading `visually` | Inspector verdict over a current screenshot | The condition concerns appearance that accessibility text cannot establish |

For nonvisual waits, one bounded hierarchy capture supplies the literal, focus and tree checks for that poll. `visually` bypasses those checks and requires a screenshot. Tree and visual inspection use the configured inspector and can incur provider costs. No navigator request is generated for a wait.

The CLI gives each inspector-backed check the journey's bounded earlier verdicts and execution trail, and earlier screenshots for `visually` waits, as reference rather than proof. This context is built once when the wait starts and stays unchanged for every poll; see [journey memory](journey-memory.md).

## One deadline and serial checks

The first check starts immediately. After a completed negative check, the next check starts one second after completion. Checks never overlap. Hierarchy/screenshot acquisition and processing, traversal, rendering, optional evidence writes, model evaluation, joined cleanup and polling delay all spend the same monotonic elapsed budget. Captures receive the remaining duration, rather than restarting the configured limit.

Only a true evaluation completed before the deadline succeeds. A late result, including one completed exactly at the deadline, cannot satisfy the wait. Deadline expiry stops further polling, cancels the current operation and joins its owned cleanup before returning. Actual elapsed time can exceed the configured limit while cancellation cleanup finishes; the duration is an evaluation admission limit, not a universal guarantee that native CPU work terminates within an additional fixed number of milliseconds.

Production Android and iOS sessions support bounded hierarchy and screenshot capture. Other session implementations must provide the bounded overloads to execute waits; unsupported sessions fail explicitly without falling back to unbounded capture. Existing assertion and loop checks retain their previous no-deadline evaluator path.

## Outcomes and artifacts

A satisfied wait continues later journey segments. A timeout stops that journey's later segments with `Wait timed out after N seconds: <condition>`. It is an ordinary failed result: a directory suite continues subsequent journeys and exits 4 if any failed. This differs from exhaustion of a loop's body count.

An inspector failure before the overall deadline remains a fatal model failure, including a request-owned 30-second expiry under a longer wait. Its diagnostic is retained even if screenshot/request cleanup crosses the overall deadline. A shorter overall wait expiring during a pending request remains wait timeout. Caller cancellation and foreign cancellation/timeouts propagate without a completed failed wait; caller cancellation takes precedence after cleanup. Ordinary capture failures and unexpectedly early device-owned capture expiry remain execution failures.

A wait segment has `executionMode: "wait"`, no action or generated-flow entries, and an additive `wait` object containing:

- `condition` and configured `timeoutSeconds`;
- actual `elapsedMs` and the number of completed `checks`;
- optional `tier` and `reasoning` from the last completed evaluation.

A timeout before the first completed check has no tier or evaluation evidence. The segment's main reasoning identifies timeout; `wait.reasoning` retains the last completed condition reasoning. Production evidence paths contain a separate `wait-NNN` check identifier, so an unfinished later poll cannot replace evidence from an earlier completed check. Nonwait segments omit `wait`, and the suite's `formatVersion` remains 1. See [run artifacts](run-artifacts.md) for required-write precedence and model-failure exit 5.

## Preview and MCP boundary

`verity run --dry-run` displays `Wait until <condition>, up to N seconds` in console and saved Markdown. It does not capture or evaluate the condition. YAML input, normal CLI execution, directory suites, preview and JSON artifacts retain the same parsed condition and limit.

MCP `load_journey` parses and displays waits through the shared core parser. Semantic wait execution belongs to the CLI agent module; neither stdio nor HTTP adds a wait tool. The repository's MCP run/debug workflows must report a wait as requiring CLI execution rather than translate it into action YAML, reuse the literal key-loop shortcut, or claim the CLI deadline contract. Authoring can save a reviewed wait while marking it unverified until CLI execution.

## Verification scope

Virtual-time tests cover scheduling, decreasing budgets, expiry, late admission, model/caller/foreign cause distinctions and joined cleanup. Production-operation fixtures cover checked screenshot output and preservation on failure. Configured Android/iOS factory smokes cover actual hierarchy/screenshot operations, active caller cancellation, complete join, functional waits and subsequent same-session reuse. These functional receipts do not establish native owned-processing CPU expiry or a universal additional termination bound. Qualification of a changed packaged runtime belongs to its matching-host packaging checks.
