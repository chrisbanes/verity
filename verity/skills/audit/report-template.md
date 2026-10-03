# UI Audit Report

> Replace every bracketed instruction with observed, configured information. Remove unused rows and all placeholders. A link may appear only after its artifact was saved and verified.

## Summary

- **Audit:** [configured audit name]
- **Run time:** [UTC timestamp]
- **Platform:** [resolved platform]
- **Overall result:** [Findings / No findings / Incomplete]
- **Targets:** [total]; [Finding count] finding; [No finding count] no finding; [Incomplete count] incomplete; [Not inspected count] not inspected
- **Finding summary:** [concise rule-based result; do not add checks that were not configured]
- **Saved screenshot evidence:** [repeat the exact relative screenshot links used in the target table and details, or write `None`]

An overall result is `Incomplete` if any target is incomplete or not inspected, a required session close failed or is unknown, an open outcome is unknown, or a required audit/output step failed. Otherwise use `Findings` if one or more completed targets have findings, or `No findings` if every completed target has no finding.

## Targets and findings

Include one row for every configured target, including targets not reached after a failure or early exit. Use only the four target states defined in the [audit workflow](SKILL.md#report-status-and-restoration-claims). Incomplete targets may retain findings already observed; describe those in Details.

| Target | Status | Fields and rules checked | Findings | Saved evidence |
| --- | --- | --- | --- | --- |
| [target ID and description] | [Finding / No finding / Incomplete / Not inspected] | [completed checks or reason not checked] | [count and rule IDs, or `None`] | [verified links or `None`] |

For each screenshot, reuse its exact relative link target here and in Summary and Details, for example `[Screenshot](evidence/<verified-file>.png)`. Never leave the example path in a report.

## Details

### [Target ID] — [description]

- **Status:** [Finding / No finding / Incomplete / Not inspected]
- **Navigation:** [completed route and result, or the failure/early-exit reason]
- **Fields and rules:** [for each check, record the configured field and rule, expected condition, actual observation, and result]
- **Findings:** [one subsection per observed rule violation; state the rule ID, field, expected condition, and actual result. If none, write `No findings.`]
- **Evidence:** [verified relative links only; reuse the exact screenshot link from Summary and the table, or write `No evidence saved.`]

#### [Finding ID or rule ID]

[Describe the configured expectation and observed result. Remove this subsection when there is no finding.]

### [Target ID] — [description]

[Repeat the target detail block for every target. For a target not reached, use `Not inspected` and state why. For an incomplete target, state which checks or artifacts did not complete and preserve any observed finding.]

## Saved evidence

List only files that were saved and verified under this report's host-created `evidence/` directory. The report and returned text evidence are host-persisted; only a requested saved screenshot PNG requires this path to be visible to the MCP screenshot writer. Use relative paths and identify the target and evidence type. Do not include `snapshot_id` values. If a screenshot save returned an error or cancellation, do not infer that its destination is absent; follow the shared screenshot procedure and do not link it as a successful capture.

| Target | Type | Verified relative path | Contents |
| --- | --- | --- | --- |
| [target ID] | [screenshot / hierarchy / visible / focused / flow] | [relative link] | [brief description; for flow, exact submitted YAML and exact result are saved] |

If none were saved, write `No evidence files were saved.` and remove the empty row.

## Session cleanup

- **Session opened:** [Yes / No / Unknown]
- **Close attempt:** [Exactly one successful call / Exactly one failed call / Not applicable / Unknown]
- **Cleanup result:** [what the close result established; do not infer success from a missing response]
- **Restoration:** [If Android animations were disabled by setup and close succeeded, state only that those setup-changed animation scales were restored. Otherwise state `No restoration success is claimed.`]

If `open_session` returns a server-side preflight failure, record that the session did not open, no close call applied, and every target is `Not inspected`.

Do not claim that the previous app screen, position, focus, or app data was restored.
