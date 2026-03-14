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
