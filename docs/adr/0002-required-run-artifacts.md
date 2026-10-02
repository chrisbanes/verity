---
status: accepted
---

# Require result artifacts for CLI runs

Console-only journey outcomes are insufficient for CI collection and later debugging. Normal CLI runs therefore always persist a suite summary and per-journey results, using versioned JSON and relative artifact references; consumers can collect and move the complete run directory. This makes result writing part of the command outcome: a required write failure is a setup failure, while optional flow and evidence persistence may fail without changing the journey verdict.

Recorded from the July 2026 suite-artifacts design for issue #49. See the [run-artifacts spec](../specs/run-artifacts.md) for layout, failure boundaries, and stable exit codes. [Dry-run reports](../specs/dry-run.md) use a separate Markdown format.
