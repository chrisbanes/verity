# Assertion-planning research (Issue #59)

This directory holds the frozen 40-case corpus, its synthetic app/control context, the prompt, and the strict response schema. The Kotlin test-runtime host uses the production journey-step parser and hierarchy helpers. It has no model or provider integration; the only supplier is a narrow injected test seam.

Export the approved model-input projection with the test-runtime host:

```sh
./gradlew :verity:core:assertionPlanningResearch --args='export research/assertion-planning/corpus.json /private/tmp/verity-assertion-planning-requests.json' --no-scan
```

The private request file contains the 40 case IDs and bypass status plus exactly four nested model-input fields: `rawStep`, `journeySteps`, `platform`, and resolved `projectContext`. Its envelope binds the corpus, approved context, deterministic host sources, prompt, and response schema by SHA-256. Prompt and schema bytes are hashed before export. The host protects those inputs from output overwrite and writes the output file with owner-only permissions using an atomic replacement when supported.

The planner response is one JSON object with exactly `mode`, `target`, `confidence`, `uncertain`, and `reason`. The schema and runtime validator allow `visible`, `focused`, `tree`, and `visual`; visible/focused need a literal nonblank target of at most 256 Unicode code points, while tree/visual require `null`. Confidence must be finite and in `[0,1]`; the reason is nonblank and at most 512 code points; the complete response is limited to 16 KiB UTF-8. Unknown, missing, or duplicate top-level fields are rejected. Invalid responses, uncertainty, low confidence, and structural guard rejection use the actual parser baseline as fallback. A valid proposal remains available for scoring when a guard or threshold selects fallback.

The T3 runner writes one typed saved-output document for the qualification request and the 40 cases. Qualification is one successful attempt included in usage counts and excluded from quality scores. Bypass cases have status `BYPASS` and no attempt; implicit cases have `SUCCESS` or safe-category `FAILURE`. Each retained response has `responseText` and `storedTextSha256`, which must match those exact UTF-8 bytes. Redacted response text uses `redactionStatus: "REDACTED"` and a separate `originalResponseSha256`; no raw provider errors, account identity, or untyped transport/configuration objects belong in the document.

This abbreviated JSON example shows the typed shape; a real complete document has every reviewed binding and all 40 case rows:

```json
{
  "formatVersion": 1,
  "bindings": {
    "requestSha256": "<sha256>", "corpusSha256": "<sha256>",
    "contextSha256": { "context/01-app.md": "<sha256>" },
    "hostSha256": { "verity/core/build.gradle.kts": "<sha256>" },
    "promptSha256": "<sha256>", "schemaSha256": "<sha256>"
  },
  "runMetadata": {
    "model": "<exact model>", "effort": "<exact effort>", "tier": "<exact tier>",
    "cliVersion": "<exact version>", "modelRevision": null,
    "startupDurationMillis": null, "wallDurationMillis": null, "globalInstructionSources": []
  },
  "counters": {
    "totalAttempts": 33, "qualificationAttempts": 1, "caseAttempts": 32,
    "bypassCases": 8, "unattemptedCases": 0
  },
  "completed": false, "stopReason": "INTERRUPTED", "cleanupVerified": false,
  "qualification": {
    "status": "SUCCESS", "responseText": "...", "storedTextSha256": "<sha256>",
    "originalResponseSha256": null, "redactionStatus": "NOT_REQUIRED", "failureKind": null,
    "setup": { "startedAt": null, "endedAt": null, "durationMillis": 1 },
    "turn": { "startedAt": null, "endedAt": null, "durationMillis": 1 },
    "cleanup": { "startedAt": null, "endedAt": null, "durationMillis": 1 },
    "tokenUsage": { "inputTokens": null, "outputTokens": null }
  },
  "cases": [
    {
      "caseId": "text-01", "status": "SUCCESS", "responseText": "...", "storedTextSha256": "<sha256>",
      "originalResponseSha256": null, "redactionStatus": "NOT_REQUIRED", "failureKind": null,
      "setup": { "startedAt": null, "endedAt": null, "durationMillis": 1 },
      "turn": { "startedAt": null, "endedAt": null, "durationMillis": 1 },
      "cleanup": { "startedAt": null, "endedAt": null, "durationMillis": 1 },
      "tokenUsage": { "inputTokens": null, "outputTokens": null }
    },
    {
      "caseId": "authority-01", "status": "BYPASS", "responseText": null,
      "storedTextSha256": null, "originalResponseSha256": null, "redactionStatus": null, "failureKind": null,
      "setup": { "startedAt": null, "endedAt": null, "durationMillis": null },
      "turn": { "startedAt": null, "endedAt": null, "durationMillis": null },
      "cleanup": { "startedAt": null, "endedAt": null, "durationMillis": null },
      "tokenUsage": { "inputTokens": null, "outputTokens": null }
    }
  ]
}
```

Actual DTOs also carry setup/turn/cleanup timing, optional token counts, safe failure categories, and typed global-instruction source counts. The evaluator checks unique exact case IDs, all six input bindings, response-text hashes, one successful qualification, exactly 32 implicit attempts, eight bypasses with zero attempts, and verified cleanup. An incomplete, duplicate, poisoned, or mismatched output is still scored for diagnostics but `studyComplete` is false.

Evaluate a saved output without contacting a model or provider:

```sh
./gradlew :verity:core:assertionPlanningResearch --args='evaluate research/assertion-planning/corpus.json /private/tmp/verity-assertion-planning-requests.json /private/tmp/verity-assertion-planning-output.json /private/tmp/verity-assertion-planning-evaluation.json' --no-scan
```

`replay` takes the same explicit paths and repeats the offline threshold sweep at `0`, `0.5`, `0.8`, `0.95`, and `1.0` using the identical saved-output SHA-256. The primary threshold is `0.8`; the sweep reports tradeoffs and does not establish calibration or optimality. Each evaluation has 120 rows: 40 cases for current, rules, and model arms. It reports all-case and implicit-case denominators, bypasses, failures, unattempted cases, fallbacks, raw and effective exact allowed-check agreement, mode mismatch, target mismatch, structural weakening, and acceptable cheap-check coverage. Agreement compares the full allowed target, ignoring case as the helpers do; actual hierarchy evaluation retains the helpers' substring/focus matching. Synthetic false passes/failures count only actual hierarchy evaluations; unavailable checks are counted separately and never treated as correct. Qualification is excluded from quality rows.

The current baseline uses `JourneyStepParser` and the frozen fixed strategy when present. The rules baseline extracts only simple positive visible/focused labels and leaves authority-bypass cases unchanged. A runtime guard uses only the actual parsed assertion description/mode and candidate proposal; it rejects reducing negated, conditional, compound/disjunctive, or visual-property expectations to positive presence/focus checks. The evaluator separately compares proposed/effective checks with the corpus's independent allowed checks and synthetic intent labels.

## Subscription runner (root execution only)

`codex_runner.py` uses Python 3.9 or newer and the standard library. Its CLI has four required paths:

```sh
python3 research/assertion-planning/codex_runner.py \
  --requests /private/tmp/verity-assertion-planning-requests.json \
  --output /private/tmp/verity-assertion-planning-output.json \
  --ledger /private/tmp/verity-assertion-planning-ledger.json \
  --grant /private/tmp/verity-assertion-planning-root-grant.json
```

These illustrative paths do not authorize execution. Only the root controller may issue the finite live grant and launch the pinned Codex 0.159.0 executable. Fake-child tests need no Codex process, sign-in, network, device, or model call:

```sh
python3 -m unittest discover -s research/assertion-planning -p test_codex_runner.py
```

The grant binds a unique grant ID and root-selected ledger ID; reviewed candidate commit, tree and three runner-file hashes; exact independent candidate/corpus review artifact paths and hashes; exact requests and freeze digests; pinned executable path, binary hash and CLI version; fixed configuration and provider-schema digests; exact output/ledger/lock paths; fixed caps; and explicit permission for exclusive initial ledger creation. The root verifies the reviews' conclusions before issuing it. The runner verifies their exact bytes and every binding before a child launch. Digests of structured values use sorted compact JSON, unescaped Unicode UTF-8 and no trailing newline; file hashes use exact bytes. The configuration digest covers the static manifest, `requireAllInheritedIntegrationsDisabled: true`, executable identity and `providerSchemaSha256`.

A stable sibling `<ledger>.lock` holds an exclusive advisory lock for the entire run. The owner-only ledger retains immutable consumed attempts and a run state. Initial creation is exclusive; each attempt is appended and file/directory-fsynced before `turn/start`, including an attempt whose acknowledgement fails. Atomic replacements preserve history and existing parent directory permissions. A used grant or a run that sent any turn cannot relaunch under this zero-rerun contract. A new independently reviewed root grant may recover exactly one `STOPPED` startup with zero consumed attempts, the same ledger identity and unchanged request/freeze/configuration digests; its historical entry and original grant/output stay preserved. Cancelled or unfinished startups, changed inputs, a second startup recovery, and every live rerun remain rejected. Exhaustion, retries, missing reviews, changed inputs and output/ledger collisions fail before launch. The fixed budget is one qualification plus 32 implicit attempts, eight zero-attempt bypasses, 33 cumulative attempts, zero retries/reruns, 1,200 seconds for the run, 30 seconds for startup, 30 seconds for each request and at most five seconds for owned cleanup within the wall budget. CLI-internal network attempt counts remain unknown; correlated error envelopes are discarded, and `willRetry` indicates CLI intent rather than another host attempt. These counters measure host `turn/start` attempts.

The inert bootstrap permits only initialize, initialized and effective config read. It discovers inherited MCP/plugin/app names in memory, exits, then the final child disables every inherited name with safely quoted argument-array overrides and verifies the effective policy. Both children inherit existing auth/runtime ownership, with API-key environment variables removed. The fixed profile uses exact `gpt-6-luna`/`low`/`default`, OpenAI provider, ChatGPT login, read-only sandbox, no approvals, no web search or agents, no notification command, disabled integrations/tools/features, zero project-doc bytes and explicit instructions. Paginated metadata qualification requires ChatGPT auth and the exact text/low model capability; there is no model/provider fallback.

Each request owns a new ephemeral thread in an empty owned directory. It sends only the four-field projection with the frozen prompt and provider output schema. Thread policy/model/tier/instruction-source echoes are checked; unexpected repository instructions or integration drift stop the run. Global instruction sources are disclosed only as typed classes/counts, with no paths. Raw configuration, account identity, errors, stderr and transcripts are discarded. Raw text is never final-answer authority: only bounded final assistant text followed by successful, correlated terminal completion is retained. Secret-pattern redaction stores the exact saved-text hash and, when needed, a separate original hash; redacted rows require root prepublication review.

Codex's ordinary turn path forwards strict structured output. The explicit `provider_output_schema` adapter preserves the five required fields, enum, nonblank pattern and bounds, using `anyOf` for string/null targets and omitting unsupported `allOf`/`if`/`then`/`oneOf` composition. Its exact SHA is grant-bound and identical on qualification and case turns. The full canonical schema and Kotlin validator still enforce the mode-target dependency. Qualification must pass that complete application contract before any corpus attempt; terminal-success case text is retained even when application validation later rejects it, so malformed proposals remain visible to host diagnostics and deterministic fallback.

The rejection latch is armed before acknowledgement. Callbacks receive fixed refusals; ordinary/raw tool calls or outputs, unknown executable events, malformed transport and identity mismatches stop further attempts. Frames are limited to 8 MiB UTF-8, the queued relevant envelopes to 64 and retained final text to 16 KiB. Acknowledged turn timeouts recover only after bounded interrupt/unsubscribe; failed isolation or cleanup prevents study success. Cleanup closes only owned threads/process handles, joins readers, verifies exit and removes only owned temporary directories. Caller cancellation is rethrown as the same exception after bounded cleanup. The programmatic test seam substitutes only owned fake repository/executable/argv inputs and shorter deadlines; the CLI always uses the pinned profile.
