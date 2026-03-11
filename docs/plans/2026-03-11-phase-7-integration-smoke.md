# Phase 7: Integration Smoke — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Validate that CLI, agent, device, and MCP layers work together through a minimal end-to-end smoke suite.

**Architecture:** Keep smoke coverage thin and high-signal. Use one fake/mock-backed path for deterministic CI and one real-device/manual path for confidence in SDK wiring.

**Design doc:** `docs/plans/2026-03-11-verity-design.md`

**Prerequisite:** Phase 0 through Phase 6 complete. Phase 2, Phase 3, and Phase 4 must be production-ready (no production `TODO()` stubs).

---

### Task 1: Add smoke fixtures

**Files:**
- Create: `verity/cli/src/test/resources/smoke/minimal.journey.yaml`
- Create: `verity/cli/src/test/resources/smoke/context/app.md`

**Step 1: Add a minimal journey**

Use a tiny journey with one action and one assertion, for example:

```yaml
name: Minimal smoke
app: com.example.app
platform: android-tv
steps:
  - Press d-pad down
  - [?visible] Home
```

**Step 2: Add minimal context**

Add simple app context text used by smoke tests.

---

### Task 2: CLI run smoke (test-double backed)

**Files:**
- Create: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandSmokeTest.kt`

**Step 1: Add test**

Validate `verity run` can load journey, segment it, execute through test doubles, and emit pass/fail output.

**Step 2: Run test**

```bash
./gradlew :verity:cli:test --tests "me.chrisbanes.verity.cli.RunCommandSmokeTest"
```

Expected: PASS

---

### Task 3: MCP smoke (stdio + HTTP)

**Files:**
- Create: `verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerSmokeTest.kt`

**Step 1: Add tests**

Use a test-double session manager to validate, at minimum:
- `open_session`
- `check_visible` (case-insensitive behavior)
- `capture_hierarchy`
- `close_session`

Run once via stdio wiring and once via HTTP wiring.

**Step 2: Run test**

```bash
./gradlew :verity:mcp:test --tests "me.chrisbanes.verity.mcp.VerityMcpServerSmokeTest"
```

Expected: PASS

---

### Task 4: Manual real-device smoke checklist

**Files:**
- Update: `README.md` (or existing runbook section) with a short manual smoke checklist

**Step 1: Add checklist**

Document a minimal manual path:
1. Start MCP server (`verity mcp --transport stdio` or `http`)
2. Open session on a connected device
3. Run one key press / one flow
4. Run one visible check and one hierarchy capture
5. Close session and verify cleanup

---

### Task 5: Full smoke gate

**Step 1: Run the smoke targets together**

```bash
./gradlew :verity:mcp:test :verity:cli:test
```

Expected: ALL PASS

---

## Verification

Integration smoke is complete when:
- CLI smoke test passes.
- MCP smoke tests pass for both stdio and HTTP transport.
- `check_visible` behavior is verified as case-insensitive.
- Manual real-device smoke checklist is documented and has been executed at least once.
