# Verity Shared Procedures

## Prerequisites

Resolve the journey's name, app ID and platform before device work. Wire platform values are `android-tv`, `android` and `ios`; map parsed `ANDROID_TV`, `ANDROID_MOBILE` and `IOS` to those values.

1. Connect the host agent to Verity MCP over stdio or HTTP. Resolve the journey with `list_journeys`/`load_journey` before opening a device session; these tools need no session. `load_journey` returns identity and typed steps, not segments or generated flows.
2. Use `get_context` for bundled platform/Maestro guidance and available project context. Respect required-context errors. Resolve app-specific details rather than guessing them. See [project context](../../../docs/specs/project-context.md).
3. After the workflow's execution confirmation, call `open_session(platform, device?)`. Omit `disable_animations` to use the configured server default; pass an explicit value only when the user requests that override. The server performs [device preflight](../../../docs/specs/preflight-checks.md); show its errors and remediation before retrying. Local `adb devices` or `xcrun simctl list` can help diagnose co-located devices, but a remote HTTP client need not have those tools.
4. Store the actual returned `session_id` only after a successful open. Every later exit follows [Session Cleanup](#session-cleanup). If opening fails, there is no opened session to close. An error encountered after opening, including missing context, requires cleanup.

Server defaults come from [project configuration](../../../docs/specs/project-configuration.md). MCP callers own flow generation and semantic evaluation; CLI model settings and result writers are separate facilities.

## Flow Generation

For public MCP `run_flow`, generate Maestro YAML using the bundled reference returned by `get_context` and available app-specific context. The autonomous CLI uses a separate internal structured-action schema.

- Start with `appId`, then a document separator and an ordered commands list.
- Add appropriate animation waits after navigation and `extendedWaitUntil` for content loading, using the current Maestro guidance.
- Keep screenshots and Verity assertions outside action flows, at their segment checkpoints.
- If actions depend on current focus, inspect `capture_focused_tree(session_id, filter: "focus")`, falling back to `capture_hierarchy(session_id, filter: "focus")` when its omission metadata hides the needed nodes. Read focus from the hierarchy, never from screenshots.
- A debugger previews every generated or edited flow, including any app launch/setup, before offering execution.

```yaml
appId: com.example.demo
---
- pressKey: Back
- waitForAnimationToEnd
```

Pass the accepted YAML exactly as `run_flow(session_id, yaml)`. Read the returned text: `SUCCESS` means execution succeeded; `FAILED: ...` means it failed even if the MCP error flag is false. MCP errors are separate failures. These workflows use the default calls and omit focus-wait options. For a single named key, call `press_key(session_id, key)` and read its default `SUCCESS` or `FAILED: ...` text in the same way. Optional observed focus waiting returns structured evidence; see the [focus-wait contract](../../../docs/specs/focus-change-waiting.md) before requesting it.

## Step Classification

Derive zero-based segments from the parsed steps as `JourneySegmenter` does:

- Accumulate actions until the next assertion; those actions and that assertion form one segment.
- Flush pending actions before a standalone loop or wait, each of which forms its own segment.
- Preserve assertion-only segments and trailing actions without assertions.

For example, actions A and B followed by assertion C, pending action D, standalone loop E, assertion-only F and trailing action G give the segments `[A,B]+C`, `[D]`, `[E]`, `[F]` and `[G]`.

Batch consecutive static actions only within their segment. Evaluate its assertion before running the next segment's actions. A loop body keeps its authored action order and condition boundary. Parsing/generation remains the caller's responsibility; see [loop conditions](../../../docs/specs/loop-conditions.md).

A typed `Wait` is a standalone, action-free segment with its parsed condition and timeout. `load_journey` displays it, but semantic wait execution and its deadline belong to normal CLI `verity run`. Do not turn a wait into Maestro action YAML or use `run_loop` as a replacement. In MCP run/debug, report the CLI requirement and leave the wait unexecuted; close any opened session. Author can save an approved wait while recording that MCP exploration has not verified it. See [wait conditions](../../../docs/specs/wait-conditions.md).

## Loop Strategy

Pick the first rule that applies:

1. **Authored journey loop** (run, debug, author): execute it with [Loop Execution](#loop-execution), which honours the authored body and bound. `run_loop(session_id, action, until, max)` may replace it only for a single raw key with a literal visible-text condition whose semantics match exactly. Pass the parsed maximum explicitly, because the tool defaults to 10. It returns `SATISFIED` or `NOT SATISFIED` text and an iteration count.
2. **Target-seeking navigation** (reach a stated target with no authored iteration contract, such as an audit `kind: "loop"` step or exploratory repositioning in author or debug): use `run_loop` under the same single-key, literal-text match. Otherwise use [Overshoot and Correct](#overshoot-and-correct) when every key or scroll only moves focus or the viewport (directional keys, scroll, swipe). Anything that activates, selects, types or submits uses [Loop Execution](#loop-execution) with the caller's bound.

Semantic, focus, visual and multi-action authored loops use Loop Execution; MCP does not expose the agent module's semantic evaluator.

## Loop Execution

Show the complete ordered body, condition and parsed maximum before execution. A journey loop defaults to 20; author an explicit non-negative bound. Split semicolon-separated bodies into nonempty ordered instructions. Reject empty components and resolve ambiguous edits before execution.

1. Evaluate the current condition before any body, using [Assertion Evaluation](#assertion-evaluation).
2. If satisfied, execute no body. A zero maximum still checks once.
3. Otherwise generate and execute one complete ordered body through `run_flow`. Debugging requires preview/choice before each newly generated or edited body.
4. Count an iteration only after the whole body succeeds. Then check the condition again, including after the last permitted body.
5. Stop at satisfaction, the maximum, user stop or failure. A failed body stops without counting it or performing a post-body condition check. Report execution failure separately from the last condition result.

Authored loops execute complete bodies with a check after each; every body and checkpoint stays within its bound.

For an unprefixed condition, first check the complete literal condition with `check_visible`. If it does not match, recognise only `<target> is focused`, `<target> has focus` and `focus is on <target>` as focus forms; their `check_focused` result is final, including false. Otherwise evaluate the current hierarchy from `capture_focused_tree` or `capture_hierarchy` under the [Hierarchy Reuse](#hierarchy-reuse) bounds rule. A leading `visually` requires a current screenshot instead. Seeing a target label alone cannot satisfy a false focus check. The [loop specification](../../../docs/specs/loop-conditions.md#one-current-state-check) defines these tiers.

## Overshoot and Correct

Reach a stated target with side-effect-free movement by batching an estimate, then correcting once from evidence. It never executes an authored journey loop.

1. Estimate the presses from current evidence, erring high. When the target is in the captured evidence, use its observed distance plus one. Otherwise use one viewport of visible items in the movement direction. Stay within the caller's bound (for example, the audit step's `max`).
2. Send the estimate as one batched `run_flow`.
3. Inspect once with the cheapest check that evaluates the `until` condition and, if unsatisfied, locates the correction:
   - `check_visible` when presence of literal text is the condition;
   - `capture_focused_tree(session_id, filter: "focus")` for focus or position targets;
   - `capture_hierarchy` for structure outside the focused region;
   - `capture_screenshot` only for a `visually` condition.
4. If the condition is unsatisfied, run one precise correction flow computed from that inspection, then inspect once again. Allow at most two correction flows.

Success is the condition satisfied on an inspection after the batch or a correction. Failure is any of:

- a `FAILED: ...` flow result or an MCP error;
- the condition still unsatisfied after the second correction;
- an inspection that cannot locate the target or the current position, so the next move would be a guess;
- a correction that would exceed the caller's bound.

Report failure as a navigation failure; audit marks the target `Incomplete`.

## Assertion Evaluation

Choose the cheapest sufficient evidence, retaining the parsed or user-approved mode:

| Mode | Tool and basis |
| --- | --- |
| `[?visible]` | `check_visible(session_id, text)`: deterministic case-insensitive substring |
| `[?focused]` | `check_focused(session_id, text)`: existing lenient focus detection. Use `capture_focused_tree` when the check needs the focused node's context |
| `[?tree]` | `capture_focused_tree(session_id)` when the claim concerns the focused region and the response reports no omission affecting it; otherwise `capture_hierarchy(session_id)`. The caller evaluates the captured accessibility structure |
| `[?visual]` | `capture_screenshot(session_id)` and caller evaluation of the actual current image |
| `[?]` | Use the mode reported by `load_journey`; parser heuristics infer it and never automatically choose FOCUSED |

Loop and `until` conditions keep the literal, focus, tree, then `visually` tiers. `capture_focused_tree` and `capture_hierarchy` are both tree-tier evidence under the same bounds rule. `diff_hierarchy` is change evidence only, never an assertion verdict on its own.

Report deterministic tool input/result and its basis. For tree/visual checks, explain how the captured state supports the verdict. Missing evidence, a tool error or failed/invalid external evaluation is a failure, never a fabricated pass. `load_journey` has no assertion-strategy argument; server configuration does not override its INFER parser behaviour.

## Hierarchy Reuse

A previous `capture_hierarchy` or `capture_focused_tree` result may answer a later check only when all of these hold:

1. It is the same open session.
2. Since the capture, only non-mutating tools have run: `check_visible`, `check_focused`, `capture_hierarchy`, `capture_focused_tree`, `capture_screenshot`, `diff_hierarchy`, `get_context`, `list_journeys` and `load_journey`. Any `run_flow`, `press_key` or `run_loop` call invalidates the capture whatever its result, as does a session reopen or a known manual or background interaction.
3. The capture was taken after the preceding action settled, and the condition does not concern loading or time-varying content.
4. The capture's filter and bounds contain the needed nodes. A `focus` filter, or a `capture_focused_tree` response with omission or truncation metadata, supports only claims inside its returned content.

Otherwise capture again. A `visually` or `[?visual]` check always needs a current screenshot. Keep returned snapshot IDs with hierarchy evidence.

## Journey Authoring

Write journey steps as intent, then state what must be true:

- Describe what to do and what must be true. Project app context (`get_context`, `verity/skills/context/app.md`) describes how the UI behaves, so propose app-context notes instead of embedding UI mechanics in steps.
- Default to loops for repeated movement. Use a bare key-press step only when the exact count is the point of the step.
- Use `Wait until <condition> [up to N seconds]` for state changes instead of fixed-duration waits.
- Prefix a loop or wait condition with `visually` only when screenshot evidence is needed.

```yaml
steps:
  - Open the Settings screen
  - Scroll down until Account is visible up to 5 times
  - Press Down 3 times
  - "Wait until Sync complete is visible up to 15 seconds"
  - "Wait until visually the profile picture is loaded up to 10 seconds"
```

## Screenshot Evidence

For a screenshot to view immediately, call `capture_screenshot(session_id)` for inline JPEG content. For a report, authoring reference or debugging evidence that must remain on disk, pass the optional string `save_to_file` and use the absolute path returned in `Screenshot saved to: <normalised absolute path>`.

Relative requests resolve against the MCP server process working directory, not the device or the report directory. The parent directory must already exist and be writable; capture does not create it. Choose an unused destination. Existing files, directories, valid or dangling symlinks, and entries appearing during publication are never overwritten. On a collision, choose another path or explicitly remove your own existing output before retrying.

Capture stages a unique PNG beside the destination, validates the complete image, and publishes it atomically without replacement using a hard link. The filesystem must support hard links. If publication is unsupported or fails, retry with an unused path in an existing writable directory on a supported local filesystem. Do not copy partial staging bytes to the requested destination as a fallback.

Cancellation observed before publication admission does not publish a destination. Admission is the cancellation check immediately before the blocking link operation. Cancellation racing after admission, including before the actual link or after publication, may leave a complete PNG. Do not assume cancellation means no output, and never delete a published destination as rollback for a failed or cancelled capture.

The tool attempts to delete only its own staging file, including on failure or cancellation. If deletion fails, its diagnostic names the staging path that may remain and instructs you to remove only that artifact when permissions/filesystem allow. Keep the primary failure or cancellation and its suppressed cleanup diagnostic together when investigating. A cleanup failure after publication reports an error rather than a success path; the complete destination can remain alongside the staging file. Do not claim either was removed. After inspecting the error, explicitly remove only the reported staging artifact; preserve the published output for its caller.

Saved PNGs survive `close_session`. Reference the actual returned absolute path in reports and keep the file available while those reports or debugging steps need it. The caller is responsible for eventual deletion after use; these files are not automatically registered as CLI run artifacts.

## Session Cleanup

Use a finally-style exit: after every successfully opened session, attempt `close_session(session_id)` on completion, user stop, tool/generation/evaluation error, failed save/parse or cancellation. Preserve the original failure while reporting cleanup failure separately. Do not claim a close or restoration succeeded when its response failed or was unavailable.

Closing releases the session and attempts to restore saved Android animation scales when they were disabled and available. iOS ignores animation disabling. Closure does not restore app navigation, app data or login state, and does not delete saved journeys or screenshots. Follow [Screenshot Evidence](#screenshot-evidence) for eventual caller cleanup; preserve user files.
