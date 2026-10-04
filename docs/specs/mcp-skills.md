# MCP skill workflows

Verity's repository provides three workflows for an external agent connected to its MCP server:

| Workflow | Use when | Interaction |
| --- | --- | --- |
| [Run](../../verity/skills/run/SKILL.md) | Execute an existing journey with assertion checkpoints | Confirm the journey first; choose continue or stop after a failure |
| [Author](../../verity/skills/author/SKILL.md) | Explore an app and write a journey | Review, edit or skip suggested actions, assertions and loops; approve the final YAML and path |
| [Debug](../../verity/skills/debug/SKILL.md) | Inspect a journey and investigate a segment | Preview each flow, then execute, skip, edit or stop; inspect current evidence and assertion reasoning |

Invoke the workflow by providing its repository `SKILL.md` file to the agent. These files require a running `verity mcp` server and an agent able to call its tools. They share the same tool catalog through stdio and HTTP. Adding a workflow directory does not register a host slash command or install the files automatically.

## Caller and server responsibilities

The agent selects and loads a journey before opening a device session. `load_journey` returns the journey identity and typed steps. The agent derives segments, generates Maestro YAML and evaluates tree or visual assertions using current evidence. The server parses journeys, performs device preflight and exposes execution, capture, deterministic checks and session closure. Model execution stays outside the MCP module; see [ADR-0001](../adr/0001-mcp-device-boundary.md).

All workflows use [shared procedures](../../verity/skills/context/procedures.md) for prerequisites, step classification, flow generation, loop execution, assertions, evidence freshness and cleanup. [Configuration](project-configuration.md) supplies applicable server defaults; [project context](project-context.md) supplies application guidance. Resolve the journey's name, app and platform before device work. A failed open returns [preflight remediation](preflight-checks.md); it creates no session for the caller to close.

Actions remain inside their assertion checkpoint. Standalone loops and assertion-only or trailing-action segments remain visible. Loop execution checks the condition immediately and after each successfully completed ordered body, honouring the parsed maximum. Semantic or multi-action loops are controlled by the agent using the [loop contract](loop-conditions.md). The literal single-key `run_loop` shortcut is suitable only when its raw-key and visible-text semantics match, with the parsed maximum passed explicitly.

## Inspectable execution

Run retains its initial confirmation, per-segment assertions, failure choices and final report. A `FAILED` flow, false deterministic check, tool error or unavailable required evidence is reported as failure. Continuing after a failure explores the resulting state; it does not establish that the original journey passes.

Debug displays the source and exact generated flow before any launch, setup or segment action. Skipping a segment executes no flow, assertion or capture for that segment. Edited YAML is shown again and the accepted replacement is passed unchanged to `run_flow`; the original journey file stays untouched unless the user separately asks to save changes. Assertion-only segments preview the check rather than inventing action YAML. Loop bodies retain their execution choices and bounds.

After executing a debug segment, capture current screenshot or hierarchy evidence when the session responds, including after a reported flow failure. Explain each verdict through its deterministic input/result or captured tree/image. Reports distinguish passed, failed, skipped and unexecuted segments and track editing separately, so an edited segment may also fail. Missing evidence remains explicit.

Author captures the opening and post-action state, reviews suggestions before adding or executing them, and pins the cheapest sufficient assertion mode. Name and goal stay separate. Saved journeys use the current `name`, `app`, `platform` and scalar-string `steps` schema, including quoted assertion prefixes and string loop syntax. The final YAML and path are reviewed before writing; `load_journey` validates the saved file without opening another session when available.

## Evidence and cleanup

Reports reference actual returned image content, hierarchy text, snapshot IDs or saved paths. Saved screenshots follow the [screenshot evidence procedure](../../verity/skills/context/procedures.md#screenshot-evidence), including no-overwrite and caller ownership. MCP workflows do not create the CLI's mandatory `result.json` or suite summary; those belong to [CLI run artifacts](run-artifacts.md).

After a successful open, attempt `close_session` on completion, user stop and every failure. Closure restores available saved Android animation scales when disabling was requested; iOS ignores animation disabling. It does not undo app navigation, app data or login state, and it does not delete saved journeys or screenshots. Report closure or restoration failure separately from the primary failure.
