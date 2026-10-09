# Verity Debug

Inspect a journey one segment at a time through Verity MCP. Preview generated Maestro YAML, then choose whether to execute, skip, edit or stop.

## Resolve and Inspect

Use `load_journey(path)`, or `list_journeys` followed by the selected path, before opening a session. Show the resolved name, app ID and platform and use shared [Step Classification](../context/procedures.md#step-classification) to derive zero-based segments from the typed steps. Obtain any needed context and follow [Prerequisites](../context/procedures.md#prerequisites) after the user agrees to connect.

`load_journey` supplies parsed steps, not generated flows. The caller segments and generates them. Show source step references, actions, assertion mode, loop body/condition/maximum and segment count.

## Preview and Choose

Before any app launch/setup or segment action, generate YAML using shared [Flow Generation](../context/procedures.md#flow-generation) and display its exact contents. Launch/setup is a separate flow with the same execute, skip, edit or stop choices below. Obtain an explicit execute choice before running the original or accepted edited setup flow; showing its YAML alone does not permit execution. Skip performs no setup flow, and stop reaches session cleanup. Keep Verity assertions outside YAML.

For each segment, show its index, original source, planned actions/assertion and exact generated flow. An assertion-only segment has no action YAML: preview the check/evidence instead. Offer:

- **Execute:** pass the displayed accepted flow exactly as `run_flow(session_id, yaml)`, then evaluate this checkpoint.
- **Skip:** record skipped and issue no flow, check or capture for that segment. Reassess later state; skipping is not a pass.
- **Edit:** accept segment-local replacement Maestro YAML, validate its `appId`/commands shape and current Maestro syntax, then redisplay it for acceptance before execution. Malformed edits return for correction and never execute. Keep the original journey file unchanged unless saving a change is separately requested. `edited=true` is independent of success/failure.
- **Stop:** leave remaining segments unexecuted, report the partial exploration and close the session.

An assertion-only segment offers execute-check, skip or stop. For a loop, preview its complete body, condition and maximum, then apply shared [Loop Execution](../context/procedures.md#loop-execution); an exercised loop segment never uses `run_loop` or [Overshoot and Correct](../context/procedures.md#overshoot-and-correct), so every body gets its own preview. Check initially; a satisfied condition performs no body. Each newly generated or edited complete body requires its own preview and execution choice. Count only successful complete bodies; honour the maximum. Exploration outside a journey step, such as repositioning the user requests, may use Overshoot and Correct.

A wait segment has no action flow. Show its condition and parsed time limit, explain that semantic execution requires CLI `verity run`, and leave it unexecuted through MCP before session cleanup. Do not claim a wait pass from a single exploratory capture or translate it to the key-loop tool.

## Capture and Explain

Interpret returned `SUCCESS`/`FAILED: ...` text separately from MCP error flags. After an actual action flow, capture a current screenshot and/or hierarchy even on reported execution failure if the session still responds. Retain returned content, snapshot ID or the actual saved absolute path using [Screenshot Evidence](../context/procedures.md#screenshot-evidence). Capture failure is explicit unavailable evidence.

Evaluate assertions through shared [Assertion Evaluation](../context/procedures.md#assertion-evaluation). Show deterministic tool input/result and basis, or explain the tree/image evidence supporting the verdict. An assertion-only execution still obtains its required current check/capture. Never infer a pass without evidence. Edited or failed flows invalidate captures under [Hierarchy Reuse](../context/procedures.md#hierarchy-reuse).

On execution, assertion, capture or external evaluation failure, show the reason and offer continue or stop. Continuing after a failure or skip is exploratory state, not proof that the original journey passes. If a successful flow has no assertion, its pass means execution succeeded, not that an unstated UI expectation was verified.

## Report and Close

Report every segment, separating outcome from edits:

| Segment | Original / replacement | Outcome | Edited | Evidence and reasoning |
| --- | --- | --- | --- | --- |
| 0 | Original launch flow | passed | false | Flow SUCCESS; Home visible |
| 1 | Original navigation | skipped | false | No execution/check/capture |
| 2 | Accepted replacement YAML | failed | true | Flow FAILED; current hierarchy retained |
| 3 | Original assertion | unexecuted | false | User stopped |

Identify replacement references, actual captured evidence, missing evidence and the effect of skips/edits on the original journey. MCP does not manufacture the CLI's run artifacts or result schema.

Every exit follows shared [Session Cleanup](../context/procedures.md#session-cleanup), including tool, generation, evaluation, edit/save and capture failures. Report cleanup errors separately.
