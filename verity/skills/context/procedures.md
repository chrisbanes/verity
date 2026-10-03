# Verity Shared Procedures

## Prerequisites

Resolve the journey's name, app ID and platform before device work. Wire platform values are `android-tv`, `android` and `ios`; map parsed `ANDROID_TV`, `ANDROID_MOBILE` and `IOS` to those values.

1. Connect the host agent to Verity MCP over stdio or HTTP. Resolve the journey with `list_journeys`/`load_journey` before opening a device session; these tools need no session. `load_journey` returns identity and typed steps, not segments or generated flows.
2. Use `get_context` for bundled platform/Maestro guidance and available project context. Respect required-context errors. Resolve app-specific details rather than guessing them. See [project context](../../../docs/specs/project-context.md).
3. After the workflow's execution confirmation, call `open_session(platform, device?, disable_animations: true)`. The server performs [device preflight](../../../docs/specs/preflight-checks.md); show its errors and remediation before retrying. Local `adb devices` or `xcrun simctl list` can help diagnose co-located devices, but a remote HTTP client need not have those tools.
4. Store the actual returned `session_id` only after a successful open. Every later exit follows [Session Cleanup](#session-cleanup). If opening fails, there is no opened session to close. An error encountered after opening, including missing context, requires cleanup.

Server defaults come from [project configuration](../../../docs/specs/project-configuration.md). MCP callers own flow generation and semantic evaluation; CLI model settings and result writers are separate facilities.

## Flow Generation

For public MCP `run_flow`, generate Maestro YAML using the bundled reference returned by `get_context` and available app-specific context. The autonomous CLI uses a separate internal structured-action schema.

- Start with `appId`, then a document separator and an ordered commands list.
- Add appropriate animation waits after navigation and `extendedWaitUntil` for content loading, using the current Maestro guidance.
- Keep screenshots and Verity assertions outside action flows, at their segment checkpoints.
- If actions depend on current focus, inspect `capture_hierarchy(session_id, filter: "focus")`; never infer focus from screenshots.
- A debugger previews every generated or edited flow, including any app launch/setup, before offering execution.

```yaml
appId: com.example.demo
---
- pressKey: Back
- waitForAnimationToEnd
```

Pass the accepted YAML exactly as `run_flow(session_id, yaml)`. Read the returned text: `SUCCESS` means execution succeeded; `FAILED: ...` means it failed even if the MCP error flag is false. MCP errors are separate failures. The current `await_focus_change` option performs an animation wait; do not use it as evidence of an observed focus change. `press_key` currently takes `session_id` and `key` only.

## Step Classification

Derive zero-based segments from the parsed steps as `JourneySegmenter` does:

- Accumulate actions until the next assertion; those actions and that assertion form one segment.
- Flush pending actions before a standalone loop, which forms its own segment.
- Preserve assertion-only segments and trailing actions without assertions.

Batch consecutive static actions only within their segment. Evaluate its assertion before running the next segment's actions. A loop body keeps its authored action order and condition boundary. Parsing/generation remains the caller's responsibility; see [loop conditions](../../../docs/specs/loop-conditions.md).

<a id="loop-execution-overshoot-and-correct"></a>

## Loop Execution

Show the complete ordered body, condition and parsed maximum before execution. A journey loop defaults to 20; author an explicit non-negative bound. Split semicolon-separated bodies into nonempty ordered instructions. Reject empty components and resolve ambiguous edits before execution.

1. Evaluate the current condition before any body, using [Assertion Evaluation](#assertion-evaluation).
2. If satisfied, execute no body. A zero maximum still checks once.
3. Otherwise generate and execute one complete ordered body through `run_flow`. Debugging requires preview/choice before each newly generated or edited body.
4. Count an iteration only after the whole body succeeds. Then check the condition again, including after the last permitted body.
5. Stop at satisfaction, the maximum, user stop or failure. A failed body stops without counting it or performing a post-body condition check. Report execution failure separately from the last condition result.

Never overshoot, correct, batch across iterations or stop midway through an authored body to bypass its bound or checkpoints. `run_loop(session_id, action, until, max)` is an alternative only for a single raw key and a literal visible-text condition with exactly matching semantics; pass the parsed maximum explicitly because the tool defaults to 10. It returns `SATISFIED` or `NOT SATISFIED` text and an iteration count. Semantic, focus, visual and multi-action loops use the caller-controlled procedure above; MCP does not expose the agent module's semantic evaluator.

For an unprefixed condition, first check the complete literal condition with `check_visible`. If it does not match, recognise only `<target> is focused`, `<target> has focus` and `focus is on <target>` as focus forms; their `check_focused` result is final, including false. Otherwise evaluate the current hierarchy. A leading `visually` requires a current screenshot instead. Seeing a target label alone cannot satisfy a false focus check. The [loop specification](../../../docs/specs/loop-conditions.md#one-current-state-check) defines these tiers.

## Assertion Evaluation

Choose the cheapest sufficient evidence, retaining the parsed or user-approved mode:

| Mode | Tool and basis |
| --- | --- |
| `[?visible]` | `check_visible(session_id, text)`: deterministic case-insensitive substring |
| `[?focused]` | `check_focused(session_id, text)`: existing lenient focus detection |
| `[?tree]` | `capture_hierarchy(session_id)` and caller evaluation of the captured accessibility structure |
| `[?visual]` | `capture_screenshot(session_id)` and caller evaluation of the actual current image |
| `[?]` | Use the mode reported by `load_journey`; parser heuristics infer it and never automatically choose FOCUSED |

Report deterministic tool input/result and its basis. For tree/visual checks, explain how the captured state supports the verdict. Missing evidence, a tool error or failed/invalid external evaluation is a failure, never a fabricated pass. `load_journey` has no assertion-strategy argument; server configuration does not override its INFER parser behaviour.

Reuse captured hierarchy only when no intervening device mutation or uncertain state change has occurred. Text entry, scrolling, edited/failed flows and background changes can invalidate freshness as well as navigation. If uncertain, capture again. Keep returned snapshot IDs with hierarchy evidence; a filtered hierarchy may not contain everything needed for a structural check.

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
