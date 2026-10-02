# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

This repo uses a single-context layout: `CONTEXT.md` and `docs/adr/` at the repo root.

## Before exploring, read these

- **`docs/architecture.md`**: the existing architecture reference.
- **`CONTEXT.md`** at the repo root: the domain vocabulary, when present.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in.
- **`docs/specs/`**: read implemented behavior specs relevant to the change. Use `docs/README.md` as the index.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

```text
/
├── CONTEXT.md
└── docs/
    ├── architecture.md
    ├── README.md
    ├── specs/
    └── adr/
        ├── 0001-<decision>.md
        └── 0002-<decision>.md
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_
