# Verity Author

Collaboratively create a journey by exploring a live device, reviewing each proposed step before adding or executing it.

## Resolve Identity

Resolve the journey name, app ID and platform (`android-tv`, `android` or `ios`) before device precheck, opening or capture. Use context for suggested defaults and confirm them; ask for missing values. Keep the display name separate from the goal and filename slug. A later goal does not replace the agreed name.

Resolve the output directory from the selected/configured journey location. If none is established, propose `verity/journeys/<slug>.journey.yaml` as a repository convention and confirm the path. No MCP tool saves a journey automatically.

Follow shared [Prerequisites](../context/procedures.md#prerequisites) after the user agrees to explore. All exits after a successful open follow [Session Cleanup](../context/procedures.md#session-cleanup).

## Capture the Starting State

Call `capture_screenshot(session_id)` and show the image, then `capture_hierarchy(session_id)` to describe the accessible state. Ask whether this is the intended starting point. Use [Screenshot Evidence](../context/procedures.md#screenshot-evidence) for a retained reference PNG.

Ask what the journey should test. Retain that goal as authoring context while keeping the resolved name.

## Review Steps Together

Repeat until the user finishes:

1. Propose a plain-English action based on the current state and platform: D-pad movement may suit TV, while taps/swipes suit mobile or iOS. Word every proposal under [Journey Authoring](../context/procedures.md#journey-authoring) and offer accept, edit or skip. Redisplay an edited proposal and obtain acceptance before executing it or adding it.
2. Generate the accepted action's flow through shared [Flow Generation](../context/procedures.md#flow-generation). Execute only after review; capture updated state before the next proposal. Report failed execution honestly and let the user retry, revise, retain an explicitly unverified step or skip it; never represent a failed action as successfully exercised. Skipped proposals are not added.
3. Propose the cheapest sufficient assertion using the [Assertion Evaluation](../context/procedures.md#assertion-evaluation) mode mapping: pin that mode and explain the choice, then offer accept, edit or skip before adding/checking it.
4. Offer another action, a loop, a wait, a reference screenshot or finish. For a wait, review its condition and positive whole-second limit; use `Wait until <condition> up to N seconds`, defaulting to 20 seconds when omitted. Semantic waits are executed by the CLI, so mark an authored wait unverified during MCP exploration. For a loop, review/edit the complete ordered body, condition and explicit maximum before adding or executing it. Execute a proposed loop step with shared [Loop Execution](../context/procedures.md#loop-execution) and current scalar grammar: semicolon-separated nonempty instructions followed by `until` and an anchored `up to N times` limit. Show any replacement body before executing it. Repositioning that is not itself a proposed step may use [Overshoot and Correct](../context/procedures.md#overshoot-and-correct).

Maintain a reviewed draft and distinguish steps actually exercised from unverified edits. After an edit or failed flow, follow [Hierarchy Reuse](../context/procedures.md#hierarchy-reuse).

## Review and Save

Show final YAML and the exact proposed output path, with any proposed app-context notes listed beside it for the user to add to project context. Current journeys have `name`, `app`, `platform` and string `steps`; action/assertion/loop/wait mappings are not the schema. Quote assertion prefixes and ambiguous scalar strings. For example:

```yaml
name: Open account settings
app: com.example.demo
platform: android
steps:
  - Tap Settings
  - "[?visible] Settings"
  - "[?focused] Account"
  - "Scroll down until Account is visible up to 5 times"
  - Tap Account
  - "Wait until the account page is ready up to 10 seconds"
  - "[?tree] The account page contains a profile section"
  - "Wait until visually the profile picture is loaded up to 10 seconds"
  - "[?visual] The profile picture is visible"
```

Apply requested edits and redisplay the final draft for approval before writing with the host's file tools. Validate YAML syntax and current [journey schema](../../../docs/architecture.md#journey-format): nonempty identity, supported platform and scalar steps; reject object-form steps, unquoted prefixes or empty loop components. `load_journey(path)` can parse the saved file without opening a new session and should return the agreed identity and expected Action/Assert/Loop/Wait classifications. If this check is unavailable, report syntax/schema review and the unrun parser check separately. A write or parse failure is reported and still reaches cleanup.

After a verified save, point to the [run skill](../run/SKILL.md) or [debug skill](../debug/SKILL.md) with the saved path. These are repository files; adding a directory does not establish registered slash commands in the host.

## Close

Follow shared [Session Cleanup](../context/procedures.md#session-cleanup) on completion, early exit and every failure.
