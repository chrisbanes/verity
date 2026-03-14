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

## Session Cleanup

Always call `close_session` when done. This restores animation scales if they were disabled.
