# Verity Run

Non-interactive journey execution against a connected device.

## Prerequisites

Follow the shared prerequisites from `procedures.md`:
1. Device precheck (adb devices / xcrun simctl list)
2. Open session with `open_session(platform, disable_animations: true)`
3. Optionally load app-specific context with `get_context` (defaults are already bundled)

## Workflow

### 1. Resolve Journey

If the user provided a path:
- Call `load_journey(path)` to parse it

If no path provided:
- Call `list_journeys` to show available journeys
- Ask the user to pick one
- Call `load_journey` with the selected path

### 2. Announce Plan

Show the journey details:
- Name, app ID, platform
- Number of segments
- Assertion type breakdown (count of VISIBLE, FOCUSED, TREE, VISUAL)

Ask: "Ready to execute? [yes/no]"

### 3. Execute Segments

For each segment:

**Actions:**
- Classify each action as static or loop (see procedures.md)
- Batch consecutive static actions across segments when possible into a single `run_flow`
- Execute loops via overshoot-and-correct or `run_loop`

**Assertions:**
- `[?visible]` → call `check_visible(session_id, text)`. Report: "check_visible('text') → true/false"
- `[?focused]` → call `check_focused(session_id, text)`. Report: "check_focused('text') → true/false"
- `[?tree]` → call `capture_hierarchy(session_id)`, then evaluate the hierarchy text against the assertion. Explain your reasoning.
- `[?visual]` → call `capture_screenshot(session_id)`, then evaluate the screenshot against the assertion. Explain your reasoning.

**Optimization**: Reuse recent hierarchy if no navigation occurred since last capture.

**On failure**: Show which segment failed and why. Ask: "Continue to next segment or stop? [continue/stop]"

### 4. Final Report

After all segments complete (or after stopping), present:

**Results table:**

| Seg | Steps | Assertion | Type | Result | Notes |
|-----|-------|-----------|------|--------|-------|
| 0 | Launch app | Home | VISIBLE | PASS | check_visible found 'Home' |
| 1 | Press down x3 | TV Shows row | VISIBLE | PASS | |
| ... | ... | ... | ... | ... | ... |

**Summary**: 3-5 sentence narrative covering what was tested, what passed/failed, and any notable observations.

### 5. Cleanup

Call `close_session(session_id)`.

## Performance Tips

- Batch static steps across consecutive segments into one `run_flow`
- Prefer `check_visible` and `check_focused` (free) over `capture_hierarchy` + LLM
- Prefer `capture_hierarchy(filter: focus)` over full `capture_hierarchy` when only checking focus
- Skip re-capturing hierarchy if no navigation occurred since last capture
