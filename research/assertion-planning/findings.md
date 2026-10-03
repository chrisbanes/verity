# Assertion-planning findings — partial experiment

The current parser and conservative rules were evaluated on the frozen 40-case synthetic corpus. Rules selected more acceptable cheap checks and made fewer semantic selection errors, but still produced false passes through the existing hierarchy helpers. The subscription runner stopped during its qualification attempt, before any corpus model calls. This is an incomplete three-arm study: it provides baseline evidence, not a measured LLM comparison.

**Decision:** no production LLM feature is justified by this evidence. The preregistered go/no-go test cannot be applied to model quality because no model proposals were retained. This is an inconclusive model experiment, not evidence that the selected model performs badly or that rules are safe to ship.

## Method and denominators

The independently reviewed corpus was fixed before any model output. It contains six text, six focus, five negation, five compound, five ambiguous, five visual and eight authority cases; 17 Android TV, 14 Android mobile and nine iOS cases. The 32 implicit cases are the comparative planning population. Explicit authored modes and configured fixed strategies bypass planning in eight cases per arm.

The test-runtime Kotlin host invokes the actual journey parser and text/focus hierarchy helpers. It preserves the original expectation separately from any extracted target. Planning inputs contain only assertion text, journey steps, platform and synthetic project context. Gold checks, intent labels and synthetic evidence never enter model input.

Semantic selection is scored against independently allowed mode/target pairs. Actual false passes and false failures are scored separately against labelled synthetic hierarchy evidence. A correctly selected literal can still false-pass because the production helper uses substring matching. Tree/visual inspector verdicts are unavailable: this experiment did not run those inspectors or capture devices.

There are 80 synthetic evidence samples across 27 cases. Thirteen cases have no evaluable hierarchy samples; they are excluded from verdict denominators, not counted as correct. “Unavailable samples” below counts supplied samples requiring an unexecuted evidence tier. Each arm has 40 effective-check rows, including eight authority rows; all selection errors and acceptable cheap checks in this run occur in the 32 implicit cases.

| Metric | Current | Conservative rules |
| --- | ---: | ---: |
| Semantic selection errors, all cases | 9/40 | 4/40 |
| Semantic selection errors, implicit cases | 9/32 | 4/32 |
| Mode mismatches | 7 | 4 |
| Literal target mismatches | 2 | 0 |
| Structural weakenings | 3 | 0 |
| Acceptable cheap checks, implicit cases | 2/32 | 10/32 |
| Evaluated evidence samples | 33/80 | 42/80 |
| Unavailable supplied samples | 47/80 | 38/80 |
| False passes on evaluated samples | 3/33 | 5/42 |
| False failures on evaluated samples | 5/33 | 0/42 |

Rules expose additional cheap checks and therefore evaluate a different subset of evidence. The false-pass counts are not a controlled accuracy comparison over identical evaluated samples.

| Effective mode, all 40 cases | Current | Conservative rules |
| --- | ---: | ---: |
| Visible | 13 | 10 |
| Focused | 2 | 8 |
| Tree | 17 | 14 |
| Visual | 8 | 8 |

For `focus-01` (“Home is focused”), current inference selects visible and searches the whole phrase. Rules select focused with literal target “Home”, eliminating that selection error and its false failure. The same improvement occurs in other simple positive literal forms; rules do not resolve project aliases or ambiguous expectations. Both arms retain selection errors in `ambiguous-01`, `ambiguous-02`, `ambiguous-03` and `ambiguous-05`.

False passes remain in `text-01`, `authority-01` and `authority-05` for both arms. Rules additionally expose false passes in `text-03` and `text-04`. These are observed helper verdicts against adversarial synthetic intent labels, not silently corrected by the research host. This separates the value of extracting a better target from the correctness of evaluating it.

## Actual subscription attempt

The host was macOS 26.7 on arm64. The fixed profile was Codex CLI 0.159.0, exact `gpt-6-luna`, reasoning effort `low`, service tier `default`, OpenAI provider and the existing ChatGPT sign-in. API-key environment variables were removed. The runner required read-only filesystem policy, restricted tool networking, no approvals, web search, agents, remote control, notification command or enabled inherited integrations. The harness retained no auth files, private configuration/account payloads or runtime-database contents, and did not copy or redirect existing auth/runtime state.

A zero-model-turn preflight verified the effective policy, retained and disabled five inherited MCP entries, 22 plugins and one app, verified ChatGPT account type and exact text/low model capability, and checked ephemeral thread policy. It observed one user-global instruction source, disclosed only as a class/count. Global instruction text and Codex runtime ownership were not replaced, so complete instruction isolation is not established.

Two earlier startup records stopped with zero consumed attempts. Source-proven compatibility repairs were independently reviewed and frozen before the actual qualification turn. Those histories, grants and outputs remain preserved; no consumption was reset.

The actual run consumed one qualification `turn/start` attempt and stopped with `POISONED`. Qualification is recorded as a safe `TRANSPORT` failure; no response text was accepted. The retained record cannot establish whether the guard rejected a callback, another unexpected envelope, or a different transport/isolation condition. It does not establish model unavailability or a quality failure. Owned processes, readers and temporary directories were cleaned and verified.

| Actual usage/timing | Observed |
| --- | --- |
| Cumulative host turn/start attempts | 1 |
| Qualification attempts | 1 |
| Corpus attempts | 0/32 |
| Authority bypass cases | 8 |
| Unattempted implicit cases | 32 |
| Accepted model proposals | 0 |
| Actual run startup | 1,001 ms |
| Qualification thread setup | 120 ms |
| Actual run total wall time | 2,898 ms |
| Terminal qualification turn duration | Unavailable |
| Input/output tokens and model revision | Unavailable |
| CLI-internal network attempts | Unknown |
| Model-only compute time | Unavailable |

The wall time includes transport and owned cleanup; it is not model compute time. No arm-specific baseline latency measurement or production API cost estimate is available. Subscription usage is a host-attempt count, not a dollar saving.

The approved stop-on-qualification-failure and zero-rerun contract prevented more inference. A later attempt requires diagnosis and a newly approved finite execution grant retaining the existing one-attempt consumption.

## Incomplete model diagnostics and thresholds

[results.json](results.json) contains 120 rows and `studyComplete=false`. Its MODEL rows have 32 unattempted implicit cases, 32 deterministic fallbacks, eight authority bypasses and zero raw scored proposals. Their effective errors/verdicts reproduce current inference; these are fallback diagnostics and must not be presented as model performance. There are zero observed model corpus failures because no corpus request was sent; the qualification failure is recorded separately.

The preregistered primary confidence threshold is 0.8. Offline replay at 0, 0.5, 0.8, 0.95 and 1.0 used identical retained bytes and produced identical fallback-only metrics. With no proposals, this sweep supplies no confidence trade-off, calibration or threshold-selection evidence.

The go condition required strictly fewer effective semantic errors than rules, more acceptable cheap checks and zero observed synthetic false passes. It remains unevaluable for the model. Before considering a production feature, complete an authorised subscription comparison, independently validate held-out cases and test real-device evidence. More cheap-check coverage alone is insufficient while substring/focus verdicts can false-pass.

Any later implementation must retain the earlier visual-budget policy: count implicit visual selections per journey, including deterministic fallback; reset between journeys; unset is uncapped, zero permits none; explicitly authored/configured visual checks are exempt. Exhaustion must report missing required evidence without weakening the expectation. This research implements none of that production policy.

## Reproduction and input bindings

The public [saved-output.json](saved-output.json) is the exact typed incomplete output. It contains no retained model text, account/configuration payload, raw error or transcript. Public artifacts were checked before publication. Generate requests from the frozen corpus and replay offline; no subscription access is needed:

```sh
./gradlew :verity:core:assertionPlanningResearch --args='export research/assertion-planning/corpus.json /private/tmp/assertion-planning-requests.json' --no-scan
./gradlew :verity:core:assertionPlanningResearch --args='evaluate research/assertion-planning/corpus.json /private/tmp/assertion-planning-requests.json research/assertion-planning/saved-output.json /private/tmp/assertion-planning-evaluation.json' --no-scan
./gradlew :verity:core:assertionPlanningResearch --args='replay research/assertion-planning/corpus.json /private/tmp/assertion-planning-requests.json research/assertion-planning/saved-output.json /private/tmp/assertion-planning-replay.json' --no-scan
python3 -m unittest discover -s research/assertion-planning -p test_codex_runner.py
```

Use the managed Gradle wrapper described by the repository's agent guidance when executing these commands as an agent. The actual evaluate and replay commands used the retained private requests/output paths and generated byte-identical JSON, SHA-256 `23a44c81fce8fb88eae59e188aef59a33beeb9c341b39805bd809e9abd82ecf9`.

| Frozen input or result | SHA-256 |
| --- | --- |
| Corpus | `c2755f254894be90f6b67327458f1c2a0f6c0d0169dcc99d94073a2918215e0d` |
| App context | `6f5a16a1630db321d72b01826f7d1157f21060e9d353689ef62c9ba2b0a139fe` |
| Control context | `1f039284059ceafb84149577a1f064edf12057d9db6e9ea4d8ef0b0c442ad27a` |
| Exported request bytes | `104efb9f65bff103598f4fc7102b2b4477861d72090b26785751f9762f3baf6e` |
| Prompt | `33582c281472cd3650b3e2abfb89a114f96695b57e7dbc4f2f02946daa4994fa` |
| Canonical response schema | `67923b6603e7ca448f5d3362f5fb4b2b7d714d4a0199ec0a8f36c25650809dbb` |
| Provider schema projection | `43cae22141875034046392d5d553377a0fe71493fdc8ba095f6102b110d75bdf` |
| Frozen runner configuration | `ca65984e1034d695ed4ac4673a715d9874b46ade1133a987fd76d6dab722fd83` |
| Typed saved output | `32df647af0b575a2a12ab892c35fe21bd3a9a3138a241e3e9f292aed8a8a4e85` |

The saved envelope also binds exact host source/build hashes. Rules, guards and scoring remain in the frozen host; no gold label or prompt was retuned after qualification. The executable SHA-256 was `e89718aa1969bfc4a471277bdc4679a3a3529293de0a309909822dfd67ddb77a`; runner commit was `c53afb9cc9380ff3bff92216c9932da6bf046423`. Pinned protocol source: [Codex commit 687a119](https://github.com/openai/codex/tree/687a119f0fcaace47e1f1abcc77cec6c813fd6da).

The 30 runner tests and repository `check --no-scan` passed. Independent integrated and focused repair reviews found no actionable defect; actual model qualification remains an unresolved delivery gate. Production CLI, MCP, journey execution and UI entry points were not changed. The shared architecture note remains deferred under #107's existing ownership lock. Issue #59 remains open; this draft preserves reproducible partial evidence.
