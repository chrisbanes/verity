# Verity Shared Procedures

## Prerequisites

Before running any skill:

1. **Device precheck**: Run `adb devices` (Android) or `xcrun simctl list` (iOS) to confirm a device is connected.
2. **Open session**: Call `open_session` with appropriate `platform` and `disable_animations: true`. Store the returned `session_id`.
3. **Optional context injection**: Call `get_context` when app-specific details are needed. Verity already bundles default Maestro and platform guidance, so this step is additive.

## Flow Generation

Generate Maestro YAML using bundled defaults, plus optional injected context from `get_context` when available. Follow these rules:

- Start with `appId: <id>`, then `---`, then commands
- Add `waitForAnimationToEnd` after navigation actions
- Use `extendedWaitUntil` for content that needs time to load
- Do NOT include screenshots or assertions in flows

**Before generating**: If the flow depends on current focus position, call `capture_hierarchy` with `filter: focus` first. Never infer focus from screenshots.

## Step Classification

Classify each action step as:

- **Static**: Deterministic key press (e.g., "Press D-pad down", "Press select", "Press back"). Batch consecutive static steps into a single `run_flow`.
- **Loop**: Requires navigating through dynamic content (e.g., "Navigate down until TV Shows row"). Use the loop execution procedure.

## Loop Execution (Overshoot-and-Correct)

Instead of pressing one key at a time:

1. **Inspect First**: Call `capture_hierarchy` to understand the current view and identify the size of one viewport (e.g., number of items currently visible).
2. **Overshoot**: Generate a flow that navigates by roughly 1 full viewport (e.g., if 4 items are visible, press Down 4 times).
3. **Inspect**: Call `capture_hierarchy` again to evaluate the `until` condition.
4. **Correct**: If overshot, generate a correction flow based on the captured state.

Goal: Viewport-aware navigation + 1 inspection + at most 1 correction. Minimizes tool calls.

Alternative: Use `run_loop` tool for simple loops where single key presses are sufficient.

## Assertion Evaluation

Choose tool by assertion type:

| Type | Tool | Cost |
|------|------|------|
| `[?visible]` | `check_visible` | Free — deterministic case-insensitive substring match |
| `[?focused]` | `check_focused` | Free — deterministic focus detection |
| `[?tree]` | `capture_hierarchy` + LLM evaluation | Medium — you evaluate the tree text |
| `[?]` (inferred) | Depends on inferred mode | Varies |
| `[?visual]` | `capture_screenshot` + LLM vision evaluation | High — screenshot + analysis |

**Optimization**: Reuse recent hierarchy captures. If no navigation occurred since the last `capture_hierarchy`, skip re-capture.

## Screenshot Evidence

For a screenshot to view immediately, call `capture_screenshot(session_id)` for inline JPEG content. For a report, authoring reference or debugging evidence that must remain on disk, pass the optional string `save_to_file` and use the absolute path returned in `Screenshot saved to: <normalised absolute path>`.

Relative requests resolve against the MCP server process working directory, not the device or the report directory. The parent directory must already exist and be writable; capture does not create it. Choose an unused destination. Existing files, directories, valid or dangling symlinks, and entries appearing during publication are never overwritten. On a collision, choose another path or explicitly remove your own existing output before retrying.

Capture stages a unique PNG beside the destination, validates the complete image, and publishes it atomically without replacement using a hard link. The filesystem must support hard links. If publication is unsupported or fails, retry with an unused path in an existing writable directory on a supported local filesystem. Do not copy partial staging bytes to the requested destination as a fallback.

Cancellation observed before publication admission does not publish a destination. Admission is the cancellation check immediately before the blocking link operation. Cancellation racing after admission, including before the actual link or after publication, may leave a complete PNG. Do not assume cancellation means no output, and never delete a published destination as rollback for a failed or cancelled capture.

The tool attempts to delete only its own staging file, including on failure or cancellation. If deletion fails, its diagnostic names the staging path that may remain and instructs you to remove only that artifact when permissions/filesystem allow. Keep the primary failure or cancellation and its suppressed cleanup diagnostic together when investigating. A cleanup failure after publication reports an error rather than a success path; the complete destination can remain alongside the staging file. Do not claim either was removed. After inspecting the error, explicitly remove only the reported staging artifact; preserve the published output for its caller.

Saved PNGs survive `close_session`. Reference the actual returned absolute path in reports and keep the file available while those reports or debugging steps need it. The caller is responsible for eventual deletion after use; these files are not automatically registered as CLI run artifacts.

## Session Cleanup

Always call `close_session` when done. This restores animation scales if they were disabled.

Closing a session does not delete saved screenshot evidence. Follow [Screenshot Evidence](#screenshot-evidence) for eventual caller cleanup.
