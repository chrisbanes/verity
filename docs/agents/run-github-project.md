# Run GitHub Project

## Repository

- Host: `github.com`
- Repository: `chrisbanes/verity`
- Default branch: `main`
- Base branch: `main`

## Project

- Owner: `chrisbanes`
- Number: `13`
- URL: `https://github.com/users/chrisbanes/projects/13`
- Node ID: `PVT_kwHOAAN4ns4BlcH_`
- Filter: `none`

## Status

- Field name: `Status`
- Field ID: `PVTSSF_lAHOAAN4ns4BlcH_zhkJArw`
- Backlog name: `Backlog`
- Backlog option ID: `81aebc99`
- Todo name: `Todo`
- Todo option ID: `f75ad846`
- Ready to implement name: `Ready to implement`
- Ready to implement option ID: `e108a434`
- In progress name: `In Progress`
- In progress option ID: `47fc9ee4`
- Done name: `Done`
- Done option ID: `98236657`

## Triage

- Needs-triage label: `needs-triage`
- Needs-triage label ID: `LA_kwDORkF4lM8AAAAC6YylPw`

## Work Roles

- Epic label: `epic`
- Epic label ID: `LA_kwDORkF4lM8AAAAC6YysgA`
- Human-work label: `ready-for-human`
- Human-work label ID: `LA_kwDORkF4lM8AAAAC6YyzHg`

## Priority

- Field name: `Priority`
- Field ID: `PVTSSF_lAHOAAN4ns4BlcH_zhkJCTA`
- Options in descending order:
  1. `High`: `809f4128`
  2. `Medium`: `1625741a`
  3. `Low`: `ee7fe4f2`

The Project `Priority` field is the single source of truth for issue priority.
Do not create, read, or apply priority labels as a fallback. Add an issue to this
Project before assigning its priority, and read the field when ordering work.
An empty field means priority has not been assigned; do not assume `Medium`.

- `High`: foundational or blocking work.
- `Medium`: important follow-up or enabling workflow.
- `Low`: later, experimental, or source-dependent work.

Priority does not change queue authorization or triage readiness.

## Merge Policy

- Method: `squash`
- Issue closure: `closing-keyword`
- Required reviews: `none` (no branch protection; repository ruleset `main` is disabled)
- Required checks: `none` enforced by GitHub (no branch protection; repository ruleset `main` is disabled)
- Done automation: `set-status`
- Automation description: Enabled Project workflows `Item closed` (`120439262`, issues and pull requests) and `Pull request merged` (`120439263`) set Status to `Done`. Enabled `Auto-close issue` (`120439264`) closes issues when their Status becomes `Done`. Auto-archive is disabled; retain Done items in the Project.

Follow the change-specific validation requirements in `AGENTS.md`. Merge authority is invocation-scoped and must be granted separately.

## Queue authority

Current column membership supplies queue authorization. Backlog is human-only: agents never work or promote its items. Todo queues planning. Preserve ownership, dependencies, verified plans, and work requiring human decisions.
