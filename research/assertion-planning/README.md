# Assertion-planning research (Issue #59)

This directory contains the frozen 40-case corpus and its synthetic app/control context. The offline T1 harness tests the existing parser, a deliberately small rules baseline, and the repository's current hierarchy helpers. It does not call a model or calculate study scores.

Export the approved model-input projection with the test-runtime host:

```sh
./gradlew :verity:core:assertionPlanningResearch --args='export research/assertion-planning/corpus.json /private/tmp/verity-assertion-planning-requests.json' --no-scan
```

The export contains one request per reviewed case, with case ID and authority-bypass status as envelope metadata. Each nested `modelInput` contains exactly `rawStep`, `journeySteps`, `platform`, and resolved `projectContext`. Corpus, context, and T1 implementation-source hashes identify the exported inputs and deterministic host. Prompt and response-schema hashes remain unavailable until T2; this T1 export contains no model output or scored comparison.

The current baseline uses `JourneyStepParser` and the frozen fixed strategy when present. The rules baseline only extracts simple positive visible/focused labels and leaves authority-bypass cases unchanged. Synthetic helper checks call `HierarchyNode.containsText` and `FocusDetector.containsFocused`; their results remain separate from the corpus's independently justified intent verdicts. Hierarchy and visual checks retain the parser description as original expectation and have no current check target.
