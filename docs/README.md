# Verity documentation

Start with the [domain glossary](../CONTEXT.md) for terminology and the [architecture reference](architecture.md) for modules and execution paths. Runtime project context supplies application guidance to Verity; it is a separate concept from this repository's domain glossary.

## Behavior specifications

These specs describe implemented behavior and point to its source and tests. They replace the July 2026 feature designs; they are maintained references, not pending implementation plans.

| Specification | Covers | Original design |
| --- | --- | --- |
| [Host packaging](specs/host-packaging.md) | Host selection, native resource policy, R8-shrunk archives, release assets and qualification limits | Issues #103, #104 |
| [Directory suite runs](specs/directory-suite-runs.md) | Journey discovery, ordering, platform selection, and suite outcomes | 2026-07-07-directory-suite-runs-design.md, issue #48 |
| [Preflight checks](specs/preflight-checks.md) | Local validation and CLI/MCP responsibility boundaries | 2026-07-07-preflight-checks-design.md, issue #45 |
| [Project configuration](specs/project-configuration.md) | Defaults, precedence, compatibility, and assertion strategy | 2026-07-07-project-config-defaults-design.md, issue #46 |
| [Project context](specs/project-context.md) | Optional/required context, loaded files, and MCP responses | 2026-07-07-project-context-validation-design.md, issue #47 |
| [Dry run](specs/dry-run.md) | Device-free preview, lazy generation, and Markdown reports | 2026-07-08-dry-run-mode-design.md |
| [Hierarchy snapshot diff](specs/hierarchy-snapshot-diff.md) | MCP capture ordering, structural comparison, focus summaries and bounded JSON | Issue #51 |
| [Focus-change waiting](specs/focus-change-waiting.md) | Reusable focus identities, serial deadlines and both MCP action results | Issue #53 |
| [Loop conditions](specs/loop-conditions.md) | Semantic tiers, complete bodies, limits and model failures | Issue #88 |
| [Wait conditions](specs/wait-conditions.md) | Action-free serial checks, one elapsed budget, cleanup and additive outcomes | Issue #89 |
| [Journey memory](specs/journey-memory.md) | Bounded earlier verdicts, execution trail, reference screenshots and the saved trail | Issue #90 |
| [MCP skill workflows](specs/mcp-skills.md) | Run, author and debug choices, caller responsibilities, evidence and cleanup | Issue #55 |
| [Run artifacts](specs/run-artifacts.md) | Result schema, artifact layout, evidence, and CI exit codes | 2026-07-08-suite-artifacts-design.md, issue #49 |

## Architecture decisions

ADRs record durable trade-offs already made by the project. They are retrospective records, not new feature proposals.

- [0001: Keep LLM execution outside the MCP module](adr/0001-mcp-device-boundary.md).
- [0002: Require result artifacts for CLI runs](adr/0002-required-run-artifacts.md).

## Working documents

New specs and task plans for proposed work belong in [GitHub Issues](agents/issue-tracker.md). Accepted behavior belongs in these reference specs, terminology in `CONTEXT.md`, and qualifying architecture decisions in `docs/adr/`.

The six completed implementation plans formerly under `docs/superpowers/plans/` are removed. Their task checklists, copied source, and old command sequences remain available in Git history, along with the original designs. The older provider design under `verity/docs/plans/` is outside this migration.

- [Internal structured actions](specs/structured-actions.md): validated action lists for CLI execution and previews; public MCP YAML boundary.
