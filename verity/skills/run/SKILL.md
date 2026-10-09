# Verity Run

Execute a journey through Verity MCP, with an initial confirmation and assertion checkpoints.

## Resolve and Confirm

1. If given a path, call `load_journey(path)`. Otherwise call `list_journeys`, let the user select a returned path, then load it. Report parse errors before device work.
2. Resolve the returned name, app ID and platform and derive segments using shared [Step Classification](../context/procedures.md#step-classification). Announce the segment count and parsed VISIBLE/FOCUSED/TREE/VISUAL mode breakdown. If any segment is a Wait, explain that the CLI must execute its semantic deadline; leave the journey unexecuted through MCP and follow Session Cleanup for any opened session.
3. Ask whether to execute. A declined confirmation performs no device execution. If a session was already opened, close it through [Session Cleanup](../context/procedures.md#session-cleanup).
4. Follow shared [Prerequisites](../context/procedures.md#prerequisites), including context/defaults and server-side preflight, before execution.

## Execute Segments

For each segment in order, generate action YAML using [Flow Generation](../context/procedures.md#flow-generation). Batch static actions within this segment only. Run each loop as [Loop Strategy](../context/procedures.md#loop-strategy) selects.

Interpret actual `run_flow` text and MCP error flags; `FAILED` text is an execution failure. After successful actions, evaluate that segment's assertion through [Assertion Evaluation](../context/procedures.md#assertion-evaluation) before executing later actions. Preserve assertion-only, trailing-action and standalone-loop and wait segments. Reuse captures only under [Hierarchy Reuse](../context/procedures.md#hierarchy-reuse).

Show deterministic input/result or the captured tree/image and reasoning for each assertion. A failed flow, false assertion, tool error, unavailable evidence or external evaluation failure identifies the segment and actual reason. Offer continue or stop. Continuing is an explicit choice to explore the resulting state; it cannot turn the failed segment into a pass. On stop, mark the remaining segments unexecuted.

## Report and Close

Report after completion or early exit:

| Segment | Steps | Assertion / mode | Outcome | Evidence and reasoning |
| --- | --- | --- | --- | --- |
| 0 | Launch app | Home / VISIBLE | passed | `check_visible("Home")` returned a match |
| 1 | Press down | Settings / FOCUSED | failed | `check_focused("Settings")` returned false |
| 2 | Press select | Account / TREE | unexecuted | User stopped after segment 1 |

Describe what ran, what passed/failed and any observations. Reference actual returned hierarchy/snapshot or screenshot evidence; saved images follow [Screenshot Evidence](../context/procedures.md#screenshot-evidence). MCP does not automatically create the CLI's `result.json` or artifact directory.

Every exit follows [Session Cleanup](../context/procedures.md#session-cleanup); report close/restoration failure separately.
