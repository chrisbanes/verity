# Focus-change waiting

`press_key` and `run_flow` accept optional `await_focus_change` (JSON boolean, default false) and `focus_timeout_ms` (positive JSON integer up to 2,147,483,647, default 2,000). The timeout is validated only when waiting is enabled. These shared registrations serve both stdio and HTTP MCP.

## Observations and identity

Core `observeFocus` extracts every directly focused node using the state-only `FocusDetector.isFocused` predicate. It retains the full tree's child-index paths (`/`, `/0`, `/0/1`) and trimmed nonblank `resource-id` values. It includes empty containers when computing paths and counts resource IDs across the complete tree. Lenient `check_focused` still accepts text near focused ancestors, siblings and descendants; it has different semantics.

`hasFocusChanged` compares sets of typed identities. A resource ID uniquely occurring in both complete trees identifies a node regardless of its path. Otherwise identity combines resource ID and path, or uses path alone without an ID. Introducing or removing a nonfocused duplicate does not by itself change an unchanged focused path. Text, other attributes and ordering of the focused set do not change identity. No focus becoming some focus, loss of all focus, and gains or losses in a multiple-focus set count as changes. Two empty observations are unchanged.

Positional fallback cannot track a semantic node through sibling insertion or reordering. A whole flow that returns A → B → A before the first post-action observation supplies no evidence of B; the wait continues until a later observed change or timeout. This API does not promise to see every intermediate focus transition.

## Timing and session ownership

The session mutex covers baseline acquisition, exactly one action and the entire wait. Baseline capture has a separate budget equal to the configured timeout. Its failure or owned timeout prevents the action. After a successful action, `FocusChangeObserver.awaitChange` starts its monotonic deadline and captures immediately. Each completed unchanged capture is followed by up to 100 ms of delay; capture time is additional, so a 40 ms capture makes the next start 140 ms after the previous start. The final delay and each capture use only the remaining budget; nothing starts at or after the deadline.

The post-action deadline includes full acquisition, parsing, normalization, focus extraction, comparison and cleanup. Completion at or after the deadline is rejected. Captures run serially, and owned capture work joins before return or session-lock release. Caller cancellation and foreign cancellation propagate; they are never tool errors or owned timeouts. The observer does not manage the main driver or runner lifecycle.

The duration overload is cooperative: unsupported session implementations fail explicitly. Android retains the SDK pipeline and interruptible work plus converter checkpoints. iOS uses its existing main runner endpoint with an owned HTTP call and checked complete-body fixed-schema decoder; no-argument capture retains the SDK implementation. Finite qualification establishes measured cancellation behavior on those routes, not a universal termination bound for arbitrary SDK CPU work or arbitrary trees.

Awaited keys skip the generic animation wait. Supplied flow YAML remains one `executeFlow` call, including any explicit animation commands. Without waiting, key confirmation and its one animation wait, and flow `SUCCESS` / `FAILED: <output>` remain unchanged.

## Result

Awaited calls return JSON in one text content item:

```json
{
  "action": {"status": "succeeded", "output": "Pressed key: DPAD_DOWN"},
  "focus_changed": true,
  "timed_out": false,
  "elapsed_ms": 100,
  "focus_before": [{"path": "/0", "resource_id": "menu:home"}],
  "focus_after": [{"path": "/1", "resource_id": "menu:settings"}],
  "focus_error": null
}
```

`elapsed_ms` is actual nonnegative post-action wait time, excluding baseline and action. Evidence contains only focused `{path,resource_id}` records; internal ID counts, full snapshots and snapshot-store mutations are absent. `[]` means a successful capture observed no focus; `null` means unknown. Timeout and capture failure retain the last successfully observed after-state, or null if none completed.

| Outcome | Action status | Changed / timed out | Error / MCP isError |
| --- | --- | --- | --- |
| Observed change | succeeded | true / false | null / false |
| No change by deadline | succeeded | false / true | null / false |
| Failed action | failed | false / false | null / true; output retains the action failure |
| Baseline error | not_executed | false / false | baseline_capture_failed / true |
| Baseline owned timeout | not_executed | false / false | baseline_capture_timed_out / true |
| Post-action capture error | succeeded | false / false | focus_capture_failed / true |

Baseline and action failures have zero wait elapsed and unknown after-state. Baseline failures also have unknown before-state and null action output. Each focus error supplies `{code,message}`. A failed action never triggers a post-action capture. Normal focus timeout does not mean the action failed.

## Source and verification

- [Core observations](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/hierarchy/FocusObservation.kt) and [identity tests](../../verity/core/src/test/kotlin/me/chrisbanes/verity/core/hierarchy/FocusObservationTest.kt).
- [Serial observer](../../verity/device/src/main/kotlin/me/chrisbanes/verity/device/FocusChangeObserver.kt) and [virtual-time tests](../../verity/device/src/test/kotlin/me/chrisbanes/verity/device/FocusChangeObserverTest.kt).
- [Shared MCP registrations](../../verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt) and [both-tool contract tests](../../verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpFocusChangeTest.kt).
- [Android](../../verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/AndroidSettingsSmoke.kt) and [iOS](../../verity/smoke-tests/src/test/kotlin/me/chrisbanes/verity/smoke/IosSettingsSmoke.kt) functional smokes qualify factory setup, bounded/no-argument capture, cancellation-following reuse and lifecycle cleanup on actual platform resources. CI retains named JUnit results. Ordinary tests do not substitute for final-head platform qualification.
