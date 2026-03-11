# Phase 6: Skills — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Write the markdown skill files and shared context documents that teach an AI agent how to use Verity's MCP tools.

**Architecture:** Skills are pure markdown — no code, no Gradle module. They encode workflow knowledge as structured instructions. A shared procedures document contains prerequisites and common patterns. Context files describe the target app, platform controls, and Maestro commands.

**Design doc:** `docs/plans/2026-03-11-verity-design.md`

**Prerequisite:** Phase 0 through Phase 5 complete. For production usage, Phase 4 (`:verity:mcp`) must be **production-ready** (no tool/transport stubs), not just scaffolded.

---

### Task 1: Shared context — procedures.md

**Files:**
- Modify: `verity/skills/context/procedures.md`

**Step 1: Write the shared procedures**

```markdown
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
```

**Step 2: Commit**

```bash
git add verity/skills/context/procedures.md
git commit -m "feat(skills): write shared procedures document"
```

---

### Task 2: Shared context — tv-controls.md

**Files:**
- Modify: `verity/skills/context/tv-controls.md` (create if only placeholder exists)

**Step 1: Write the TV controls reference**

Create `verity/skills/context/tv-controls.md`:
```markdown
# TV Remote Controls

## Android TV D-pad Mapping

| Action | Maestro Key Name |
|--------|-----------------|
| D-pad Up | `Remote Dpad Up` |
| D-pad Down | `Remote Dpad Down` |
| D-pad Left | `Remote Dpad Left` |
| D-pad Right | `Remote Dpad Right` |
| Select / Enter | `Remote Dpad Center` |
| Back | `back` |
| Home | `home` |
| Menu | `Remote Media Menu` |
| Play/Pause | `Remote Media Play Pause` |
| Rewind | `Remote Media Rewind` |
| Fast Forward | `Remote Media Fast Forward` |

## Maestro YAML for Key Presses

```yaml
- pressKey: Remote Dpad Down
- pressKey: Remote Dpad Center
- pressKey: back
```

## Navigation Patterns

- **Row navigation**: D-pad Left/Right moves between items in a row
- **Vertical navigation**: D-pad Up/Down moves between rows
- **Content entry**: Select (D-pad Center) opens detail pages
- **Back navigation**: Back returns to previous screen

Always add `waitForAnimationToEnd` after navigation presses to allow transitions to complete.
```

**Step 2: Commit**

```bash
git add verity/skills/context/tv-controls.md
git commit -m "feat(skills): write TV remote controls reference"
```

---

### Task 3: Shared context — maestro.md

**Files:**
- Create: `verity/skills/context/maestro.md`

**Step 1: Write the Maestro command reference**

```markdown
# Maestro YAML Reference

## Flow Structure

Every flow starts with an `appId` header:

```yaml
appId: com.example.app
---
- pressKey: Remote Dpad Down
- waitForAnimationToEnd
```

## Common Commands

### Key Presses
```yaml
- pressKey: Remote Dpad Down
- pressKey: back
```

### Wait for Animation
```yaml
- waitForAnimationToEnd
```
Always add after navigation actions.

### Extended Wait
```yaml
- extendedWaitUntil:
    visible: "Loading"
    timeout: 10000
```
Use when content needs time to load.

### Launch App
```yaml
- launchApp:
    appId: com.example.app
    clearState: false
```

### Tap (Mobile/Tablet)
```yaml
- tapOn:
    text: "Button Label"
```

### Scroll (Mobile/Tablet)
```yaml
- scroll
```

### Input Text
```yaml
- inputText: "search query"
```

### Assert Visible (in flows)
```yaml
- assertVisible:
    text: "Expected Text"
```

Note: For Verity journeys, prefer using `[?]` assertions in the journey file rather than Maestro's built-in assertions. Journey assertions are evaluated by the orchestrator with LLM support.
```

**Step 2: Commit**

```bash
git add verity/skills/context/maestro.md
git commit -m "feat(skills): write Maestro YAML command reference"
```

---

### Task 4: Shared context — app.md placeholder

**Files:**
- Create: `verity/skills/context/app.md`

**Step 1: Write the placeholder**

This file is app-specific — it gets filled in per project.

```markdown
# App Context

This file describes the target application. Update it with:

- App package/bundle ID
- Screen descriptions and navigation structure
- Key UI patterns (content rows, detail pages, settings)
- Known quirks or accessibility issues

## Example

```
App ID: com.example.launcher
Platform: Android TV

Screens:
- Home: Spotlight carousel at top, content rows below (Movies, TV Shows, etc.)
- Detail: Backdrop image, title, synopsis, tabs (Episodes, Cast, Trailers)
- Settings: Vertical list of options

Navigation:
- D-pad Down from spotlight reaches first content row
- D-pad Right/Left moves between items in a row
- Select on an item opens its detail page
- Back returns to Home
```
```

**Step 2: Commit**

```bash
git add verity/skills/context/app.md
git commit -m "feat(skills): add app context placeholder"
```

---

### Task 5: Skill — verity-run

**Files:**
- Modify: `verity/skills/run/SKILL.md`

**Step 1: Write the skill**

```markdown
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
```

**Step 2: Commit**

```bash
git add verity/skills/run/SKILL.md
git commit -m "feat(skills): write verity-run skill"
```

---

### Task 6: Skill — verity-author

**Files:**
- Modify: `verity/skills/author/SKILL.md`

**Step 1: Write the skill**

```markdown
# Verity Author

Collaboratively create a new journey file by exploring a live device.

## Prerequisites

Follow the shared prerequisites from `procedures.md`:
1. Device precheck
2. Open session with `open_session(platform, disable_animations: true)`
3. Optionally load app-specific context with `get_context` (defaults are already bundled)

## Workflow

### 1. Resolve Journey Name

Ask the user for a journey name. The file will be saved as:
`verity/journeys/<name>.journey.yaml`

### 2. Resolve App ID

If the context specifies an app ID, confirm it. Otherwise ask the user.

### 3. Opening Snapshot

- Call `capture_screenshot(session_id)` — show the screenshot
- Call `capture_hierarchy(session_id)` — describe what's visible
- Ask: "Is this the right starting point? [yes/no]"

### 4. Get Journey Goal

Ask: "What should this journey test? (one sentence)"

Use this as the journey `name` field.

### 5. Step Authoring Loop

Repeat until the user says "finish":

#### Suggest Next Step

Based on the current screen state (screenshot + hierarchy):
- Suggest a plain-English step (e.g., "Press D-pad down to navigate to Movies row")
- Show the suggestion and ask: "Accept, edit, or skip?"

#### Ask About Assertions

After each navigation action, suggest an assertion:
- Prefer the cheapest type that works:
  - If the expected text is short and specific → suggest `[?] Text` (will infer VISIBLE)
  - If the check requires understanding structure → suggest `[?tree] description`
  - Only suggest `[?visual]` if the check involves images, colors, or visual layout
- Ask: "Add this assertion? [yes/edit/skip]"

#### What Next?

Ask: "What would you like to do?"
- **Describe an action** — user types a step, you generate and execute it
- **Add a loop** — user describes what to navigate to, you create a loop step
- **Take a screenshot** — capture current state for reference
- **Finish** — done authoring

### 6. Review

Show the complete journey YAML:

```yaml
name: <journey goal>
app: <app id>
platform: <platform>

steps:
  - <step 1>
  - <step 2>
  - ...
```

Ask: "Any edits? [looks good/edit]"

If editing, apply changes and show updated YAML.

### 7. Save

Write the journey file to `verity/journeys/<name>.journey.yaml`.

### 8. Next Steps

Offer: "Journey saved! You can run it with `/verity-run <path>` or step through it with `/verity-debug <path>` (when available)."

## Assertion Cost Awareness

Always suggest the cheapest assertion mode that would work:

1. **VISIBLE** (`[?] short text`): For checking specific text exists on screen. Free.
2. **FOCUSED** (`[?focused] text`): For checking what's currently focused. Free.
3. **TREE** (`[?tree] description`): For checking structure, relationships, or content meaning. Uses LLM.
4. **VISUAL** (`[?visual] description`): For checking images, colors, layout. Uses LLM + vision. Last resort.

When in doubt, default to VISIBLE for short checks and TREE for complex ones.
```

**Step 2: Commit**

```bash
git add verity/skills/author/SKILL.md
git commit -m "feat(skills): write verity-author skill"
```

---

## Verification

After all tasks, the skills directory should contain:

```
verity/skills/
├── context/
│   ├── procedures.md    # Shared prerequisites and procedures
│   ├── app.md           # App-specific context (placeholder)
│   ├── tv-controls.md   # TV remote control reference
│   └── maestro.md       # Maestro YAML command reference
├── run/
│   └── SKILL.md         # Non-interactive journey execution
└── author/
    └── SKILL.md         # Interactive journey authoring
```

No code to test — skills are pure markdown consumed by AI agents.
