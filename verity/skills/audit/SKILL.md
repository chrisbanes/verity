# Verity Audit

Run a reusable, read-only audit of application UI targets supplied for this run. Targets, fields, rules, navigation, and requested evidence come from the caller or project Markdown. Do not add application-specific targets, expected values, checks, or routes of your own.

Follow the shared [prerequisites](../context/procedures.md#prerequisites). Also use the shared procedures for [flow generation](../context/procedures.md#flow-generation), [step classification](../context/procedures.md#step-classification), [overshoot and correct](../context/procedures.md#overshoot-and-correct) navigation, [assertion evaluation](../context/procedures.md#assertion-evaluation), [hierarchy reuse](../context/procedures.md#hierarchy-reuse), [screenshot evidence](../context/procedures.md#screenshot-evidence), and [session cleanup](../context/procedures.md#session-cleanup). Read [project application context](../context/app.md) when resolving project Markdown. This skill defines the audit-specific input, evidence, report, and failure contract; it does not repeat those procedures.

Use the matching [report template](report-template.md) for every saved report.

## Resolve the audit input

The logical audit object has these values:

| Value | Requirement |
| --- | --- |
| `audit_name` | Optional short label used to make the report directory name. |
| `app_id` | Required only if a configured navigation flow uses an app ID. |
| `platform` | Required unless the connected MCP server's default platform is verified before session setup. Supported values are `android-tv`, `android`, and `ios`. |
| `device` | Optional device identifier; if omitted, allow the configured MCP server selection. |
| `output_dir` | Required absolute path to an existing writable host directory. If any target requests saved `screenshot` evidence, the same path must also be demonstrably visible to the MCP screenshot writer. |
| `targets` | Required non-empty list of uniquely identified targets. |

Each target has a unique `id`, a `description`, a non-empty list of explicit safe `navigation` steps, non-empty `fields` and `rules` lists, and an `evidence` list (which may be empty when no saved evidence was requested). `navigation` steps use `kind: "flow"`, `kind: "key"`, or `kind: "loop"`: flow steps provide safe read-only route instructions in `steps` (or exact YAML), key steps provide a key in `key` for `press_key`, and bounded loop steps provide an `action`, an `until` condition, and a positive finite integer `max`. Reject unknown or incomplete step shapes before session setup. Field IDs and rule IDs must be unique within their target, and every rule must refer to a configured field. Each field has an `id` and a description of what to inspect; it may provide exact `visible_text` and/or `focused_text` query strings. Each rule has an `id`, the field it evaluates, and a supplied condition. Never invent a condition or turn an observation into a finding unless it violates a supplied rule. State findings with the rule, field, configured expectation, and observed value or result.

Project configuration is Markdown returned by `get_context`, not a typed audit schema. Resolve it only from a clearly named, unambiguous audit section. A caller may instead provide the structured audit object directly. For each property explicitly present in the invocation, use that value in place of the corresponding project-context property; use context only for properties absent from the invocation. An invocation `targets` list replaces the complete context list; never merge targets by ID. An explicit empty or invalid invocation value remains invalid and does not fall back to context. If context contains conflicting definitions for a value or target, treat it as ambiguous. Do not imply that `get_context` parses audit YAML or loads `verity/config.yaml` or its `paths.output` value.

Example input (replace every placeholder with project-supplied values):

```json
{
  "audit_name": "<short audit label>",
  "app_id": "com.example.placeholder",
  "platform": "android-tv",
  "device": "<optional device identifier>",
  "output_dir": "/absolute/path/to/audit-output",
  "targets": [
    {
      "id": "target-1",
      "description": "<configured UI surface to inspect>",
      "navigation": [
        {
          "kind": "flow",
          "steps": "<explicit safe, read-only route to this target>"
        }
      ],
      "fields": [
        {
          "id": "field-1",
          "description": "<configured value or property to inspect>",
          "visible_text": "<optional exact visible-text query>",
          "focused_text": "<optional exact focused-text query>"
        }
      ],
      "rules": [
        {
          "id": "rule-1",
          "field": "field-1",
          "condition": "<configured pass condition for field-1>"
        }
      ],
      "evidence": ["screenshot", "hierarchy", "visible", "focused", "flow"]
    }
  ]
}
```

Before opening a session, resolve and validate all inputs. Stop and report the exact missing, ambiguous, conflicting, or unsupported value if any of these remain unresolved:

- No usable target list, duplicate target, field, or rule IDs, missing fields or rules, a missing `evidence` list, missing descriptions, empty navigation, or navigation that is absent or unclear.
- A navigation step whose safety cannot be established as read-only. Do not submit forms or invoke actions that change app data, account state, purchases, or settings. Navigation can change the visible screen or position.
- A platform that is absent and has no verified MCP default; a required `app_id` that is absent; or unsupported platform/device instructions.
- An evidence type outside `screenshot`, `hierarchy`, `visible`, `focused`, and `flow`. For each requested `visible` or `focused` type, require at least one exact corresponding query in the configured fields. If `flow` is requested, require a configured flow navigation step.
- A request to restore prior app data, screen, focus, or position. Session setup supports restoring Android animation-scale settings that it changed; it does not restore arbitrary app or UI state. Stop before opening if the requested outcome depends on unsupported restoration.
- An absent, relative, or host-unwritable `output_dir`; if any target requests saved `screenshot` evidence, also stop when the MCP screenshot writer's visibility of that path cannot be verified.

Do not open a device session while resolving an input or output-path failure. If a blocked request has a usable report directory, record the setup failure there; otherwise explain it to the caller. Do not describe an audit that never opened a session as inspected.

## Prepare output before session setup

Verify that `output_dir` is absolute and that the existing directory is writable through host file access. This host-writable path is required for the report and all returned text evidence. Only when at least one target requests saved `screenshot` evidence, also verify that `output_dir` is on a filesystem the MCP screenshot writer can access. If screenshot evidence is requested and root visibility is uncertain, stop before `open_session`; do not invent a transfer or fallback capture path. Do not substitute a working-directory-relative path or use a CLI output setting as evidence of MCP visibility.

Before `open_session`, create a new directory named `<UTC yyyyMMdd-HHmmss>-<safe audit slug>` directly under `output_dir`, then create its `evidence/` child using host file access. Use `audit_name` for the slug when supplied; otherwise use a safe slug from the first configured target ID. Create directories exclusively: if any candidate already exists (including a file or symlink), leave it and all contents untouched and try `-1`, `-2`, and so on. Do not overwrite. If the host cannot create the report directory or evidence child, stop before session setup. When screenshots are requested, confirm before session setup that the MCP screenshot writer can address this same report/evidence path; if it cannot be verified, stop without opening a session. Use unique evidence filenames under `evidence/` and confirm each proposed destination is unused. Host-persist returned hierarchy, visible, focused, and flow text there. For each target, name its first saved screenshot `<safe-target-id>-001.png`; use the same target ID and a per-type, zero-padded capture number in other evidence filenames.

The report is `report.md` inside the new directory and is written through host file access. Keep report links relative to that directory. Screenshot save paths use the same absolute directory only when screenshot evidence is requested and MCP visibility has been verified.

## Run the configured audit

1. Load context with `get_context` when project Markdown is needed, then resolve invocation overrides and validate the full audit object. Do not treat bundled guidance as project-specific targets or rules.
2. Check that any restoration request is supported and prepare the output directory.
3. Call `open_session` once with the resolved `platform`, optional `device`, and `disable_animations: true`, an audit-specific explicit choice. Record the returned `session_id`. If the server-side preflight fails, report the setup failure and mark all targets `Not inspected`; no session was opened. If the response is otherwise unknown or has no usable ID, report cleanup as unknown; the available interface cannot close an unidentified session.
4. For each target in configured order, perform only its explicit safe navigation. Generate and execute flow steps using the shared flow procedures. Evaluate only its supplied fields and rules using configured queries and current tool results. Navigate a `kind: "loop"` step as target-seeking navigation under [Loop Strategy](../context/procedures.md#loop-strategy) (`run_loop` or [Overshoot and Correct](../context/procedures.md#overshoot-and-correct)), using its `max` as the bound; failure there is failed navigation (next item). Reuse captures only under [Hierarchy Reuse](../context/procedures.md#hierarchy-reuse).
5. Stop crawling after failed or unknown navigation, or after a controlled early exit. Mark the current target `Incomplete` and every later target `Not inspected`. Do not turn an incomplete check into a clean result. A target is `Finding` only when its planned inspection completed and at least one supplied rule failed; it is `No finding` only when its planned inspection completed and no supplied rule failed. If inspection is incomplete after observing a violation, retain the finding in details while marking the target `Incomplete`.
6. Capture and save each requested evidence type as described below. An evidence capture or file-write failure makes the target `Incomplete`; it never creates a link to an unverified file.
7. Close a successfully identified session in exactly one `finally` cleanup path, on normal completion, controlled failure, and early exit. Make one `close_session(session_id)` attempt only; record success, failure, or unknown. Do not retry a failed or uncertain close. A failed or unknown close after a session opened (or an unknown open outcome) makes the overall result `Incomplete`, even if all target checks completed. Then finish and save the report. If report writing fails, tell the caller that `report.md` could not be persisted and give no report-success claim.

### Evidence capture and persistence

Only capture evidence requested by the target:

| Type | Capture and saved artifact |
| --- | --- |
| `screenshot` | Call `capture_screenshot(session_id, save_to_file: <unique absolute PNG path>)`. Use the returned `Screenshot saved to: <path>` value as the candidate path, then verify that it is absolute, inside this report's `evidence/` directory, and exists. Link only that verified returned file. If visual evaluation is also required, inspect a separate inline JPEG capture first and make the PNG save call at the same screen before navigation. The inline JPEG and saved PNG are separate captures. |
| `hierarchy` | Call `capture_hierarchy(session_id, filter: "content")`. Save the rendered hierarchy text, excluding its `snapshot_id`, to a unique text file and verify the saved file. A snapshot ID is session-local and is never a durable artifact or report reference. |
| `visible` | For each configured `visible_text` query, call `check_visible(session_id, text)` and save the exact query and returned `true` or `false` result to a unique text file. |
| `focused` | For each configured `focused_text` query, call `check_focused(session_id, text)` and save the exact query and returned `true` or `false` result to a unique text file. |
| `flow` | For every configured flow submission, save the exact submitted YAML and exact returned result (`SUCCESS`, `FAILED: ...`, or the exact error/unknown response) together in a unique text file. Do not reformat the YAML or describe this as a CLI run artifact. |

For every artifact, write under the prepared `evidence/` directory and verify the actual file before adding its relative link. Do not construct a screenshot link from the requested path alone. On screenshot-save error or cancellation, follow the shared [screenshot evidence procedure](../context/procedures.md#screenshot-evidence). Do not link an error result as a successful capture.

### Report status and restoration claims

Use exactly one target state for every resolved target: `Finding`, `No finding`, `Incomplete`, or `Not inspected`. Include targets after the point of failure in the report. Show each observed finding with its configured rule, field, expectation, and actual result. A failed navigation, evidence capture, evidence write, report write, or failed/unknown required session close cannot produce an overall clean audit.

Report session cleanup separately from audit findings. If `close_session` completes successfully after Android animation scales were disabled by setup, state only that the setup-changed animation scales were restored. iOS ignores that option. If close fails or its outcome is unknown, make no restoration-success claim. Do not claim restoration of an app screen, position, focus, or data.

Fill the [report template](report-template.md), remove all unused instructions and placeholders, and include only links to artifacts that were successfully saved and verified. Keep the same relative screenshot link in the summary evidence list, its target row, and that target's details. If no finding exists, say so explicitly in the summary and details. If no artifact was saved, say so without adding a placeholder link.
