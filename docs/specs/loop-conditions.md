# Loop conditions

CLI journeys can repeat an ordered body until the current screen satisfies a condition. This describes the implemented authoring, evaluation and result contract from [issue #88](https://github.com/chrisbanes/verity/issues/88).

## Authoring

A loop begins with `press`, `navigate`, `move`, `scroll`, `go` or `step`, followed by `until` and a condition. Join actions with semicolons to execute them in order as one body. Each component must contain an instruction; leading, trailing or repeated empty components are invalid. Only the first action must begin with a loop-leading verb.

```yaml
steps:
  - Press D-pad down; press D-pad right until Settings up to 3 times.
  - Press D-pad down until Settings has focus max 3
  - Scroll down; tap Settings until account page is ready for up to 3
  - Press D-pad down until visually the selected tile has a blue border 3 iterations
```

The supported anchored limit suffixes are `up to N times` (also singular `time`), `max N`, `for up to N` and `N iterations`. A final period is optional. Matching is case-insensitive; the default limit is 20. A zero limit still checks the condition once and performs no body. Existing `Loop(action, until, max)` values and journey serialization retain their shape; serialization uses the existing `up to N times` form.

Assertion prefixes such as `[?]` and `[?visual]` retain priority over loop inference. Action mapping remains platform-specific: for example, Android TV's mapped direction instruction is `Press D-pad down`. An instruction that does not map can still be handled by the navigator.

## Authoring pitfalls

A condition ending `is focused` or `has focus`, or starting `focus is on`, takes the deterministic `focus` tier unless the literal tier already matched the whole condition. The focus tier searches the hierarchy for the extracted target text only and never asks a model, so a prose target such as `a card in a row that opens a detail page is focused` never matches and the loop runs to its limit (default 20). To avoid this, quote the exact on-screen label (`"Continue Watching" is focused`), reword the condition without the focus phrases so the inspector evaluates it (for example `a card is visible`), or use `visually` for appearance.

`verity run`, including `--dry-run`, prints a non-blocking warning to stderr for each `Loop` or `Wait` condition that takes the focus tier with an unquoted target of four or more words, or any target longer than 40 characters. The warning starts with `Warning:` and names the journey file, the 1-based step number, the condition and the extracted target. It does not change stdout, the dry-run report, run artifacts or exit codes. `visually` conditions are never flagged.

Loop bodies avoid a model call only when every instruction maps to an interaction, so write the platform's mapped phrases:

- Android TV: `Press D-pad up|down|left|right|center`, `Press select|enter|back|home|menu|play|pause|rewind|fast forward` and `press keycode N`.
- Android mobile: `tap`/`click` (optionally `on`), `long press`/`hold`, `scroll up|down`, `swipe up|down|left|right`, `pull to refresh`, `press back|home|enter|volume up|volume down` and `press keycode N`.
- iOS: the same touch phrases, plus `press home|lock|volume up|volume down`.

Other phrasing, such as `Navigate down`, is unmapped. If any instruction in a body is unmapped, the navigator generates the whole body, costing one model call per iteration. Prefer the explicit mapped phrase, for example `Press D-pad down until "Settings" is focused`.

## One current-state check

A case-insensitive leading word `visually` selects screenshot inspection. The prefix is removed before inspection, and a current screenshot is required. Text, focus and tree checks cannot satisfy this condition. Use this for appearance that accessibility text cannot establish, such as colour or a rendered image. Screenshot inspection uses the configured inspector model.

A condition wholly wrapped in matching quotes, such as `"spotlight-item-"` or `'spotlight-item-'`, is *literal-only*. After trimming, it must have at least two characters, start and end with the same `"` or `'`, and not contain that quote character in between, so `"a" and "b"` and `"Continue Watching" is focused` are not literal-only. Each check searches the hierarchy for the trimmed text inside the quotes, with the same case-insensitive substring match as the `literal` tier. A match passes and a miss fails, both with tier `literal`. A literal-only condition never takes the focus, `precheck` or `tree` tiers and never requests a model. A blank quoted condition (`""`) is rejected while parsing, like a blank condition.

For other conditions, evaluation proceeds in this order:

| Tier | Check | Result |
| --- | --- | --- |
| `literal` | Case-insensitive substring match for the complete condition | A match satisfies the condition without a model request |
| `focus` | A recognised focus form, using the existing lenient focus detector | True or false is final for this check, without a model request |
| `precheck` | Opt-in [pre-check](project-configuration.md#pre-check) probability for the same PROMPT hierarchy, tried before the inspector | A confident fail, or a confident pass with `confirm-pass: false`, decides the check without an inspector request; otherwise the `tree` tier runs |
| `tree` | Inspector evaluation of the current PROMPT accessibility hierarchy | A valid inspector verdict supplies the outcome and reasoning |

The focus grammar recognises exactly `<target> is focused`, `<target> has focus` and `focus is on <target>`, case-insensitively. Targets are trimmed, with matching surrounding single or double quotes removed; blank targets are not recognised. Other focus-like prose belongs to tree inspection. Seeing the target text alone does not satisfy a false focus condition: the literal tier searches for the complete condition, not the extracted target. Literal, focus and tree work for one condition check share a single hierarchy capture.

The pre-check never changes the literal or focus tiers, and a timeout, error or uncertain probability falls back to the `tree` tier unchanged. By default (`precheck.confirm-pass`, default `true`) a confident pass does not end the loop on its own: the inspector evaluates the same hierarchy rendering once, with the same batching-hint request as an ordinary check in a batched loop, and decides with tier `tree`. If it fails the pass, the check is unmet exactly as if the pre-check had been uncertain, and a batched loop continues with the inspector's hints. A model failure during confirmation is the ordinary fatal model failure. Hierarchy evidence is saved once, as for an inspected check. In a batched D-pad loop only a confident pass is accepted, because an unmet check needs the inspector's batching hints; a confident fail falls back to the inspector.

The classifier is System One by default or OpenAI's Decisions API with `precheck.provider: decisions`; a Decisions `refusal` counts as an uncertain fallback. See [Decisions provider](project-configuration.md#decisions-provider).

With the experimental `precheck.visual-enabled` also on, a `visually` condition asks the pre-check about the screenshot it has just captured. A confident fail decides the check with tier `precheck` and keeps the screenshot evidence. A confident pass is confirmed by default: the inspector evaluates that same screenshot once and decides with tier `visual`, and an overruled pass leaves the check unmet. With `confirm-pass: false` a confident pass decides with tier `precheck`. Otherwise the inspector evaluates that same screenshot; it is never recaptured. Visual loops still check after every body. See [screenshot pre-check](project-configuration.md#screenshot-pre-check).

Choose a literal condition for visible text (quote it to keep it off the model tiers while the screen loads), a recognised focus form for navigation focus, tree inspection for relationships described by the accessibility hierarchy, and `visually` for appearance. Inspector tiers can incur provider costs; deterministic tiers do not request a model.

## Body boundaries and failure

The orchestrator checks immediately, then executes up to the permitted number of complete bodies. It checks once after each completed body, including the final permitted body, except for batched D-pad loops (below). It never checks or stops because of the condition midway through a body. The iteration count increases only after every instruction in the body succeeds.

### Batched D-pad loops

On Android TV, a loop whose body is exactly one mapped direction press (`Press D-pad up|down|left|right`) and whose condition is neither `visually`, literal-only nor a focus form (`is focused`, `has focus`, `focus is on`) may press several times before one check. Every other loop, including multi-instruction bodies, `Press select`, keycodes, generated bodies and other platforms, checks after every body. Waits, assertions and MCP `run_loop` never batch.

After each unmet check, the tree that check read predicts a batch size:

- **Tree tier.** The inspector may return the optional `target` and `estimatedIterations` fields (below). A `target` rect that matches a node gives a press count to it, unless that node already contains focus or is inside it. `notVisible` presses past the visible items, and `estimatedIterations` is used only when no target geometry resolves.
- **Literal tier** checks always pass, so they never need a prediction. Focus forms are not batched at all: their check needs no model request, so checking after every press costs little, and the check itself is the only reliable way to notice that focus reached the target early.

`FocusStepPlanner` counts steps geometrically. Candidates are focusable leaves with valid `bounds` that overlap focus across the loop direction, and items are grouped into bands by their leading edge, so a row of cards counts once when moving vertically. Focus traversal can differ from geometry, so every batch is verified.

A batch is `min(prediction, limit - completed bodies)` and is never padded. Without a prediction, the loop presses once. Each press uses the D-pad focus settle and counts as one completed body. An on-screen batch also stops as soon as the settled focus reaches the target node's child-index path (the same path, or an ancestor or descendant of it). This catches a focus order that skips a predicted step while child indices stay put. It is not a guarantee: a list that recycles children as it scrolls shifts the target's path, and off-screen and estimated batches have no target node to watch, so a skipped step there can still carry focus past the target before the check. Loops never press the opposite key, so unless focus wraps back to the target, such a loop then exhausts its limit and fails. The loop switches to one press per check for the rest of the loop when focus returns to an earlier focus in the loop (a wrap), when the check after an on-screen prediction is still unmet, or when a press of an on-screen or off-screen batch does not change focus (an edge or a failed observation). An estimated batch (from `estimatedIterations`, used when no geometric prediction applies) keeps pressing when focus does not change, including after a failed observation, because the value it counts toward (a playback position, say) can change while focus stays on one control. The next press then takes the last known focus as its baseline, so an unchanged press costs no fresh baseline capture. An estimated batch cannot detect a value edge, so at an edge it spends at most the rest of the estimate. After an off-screen or estimated batch, the next check plans again from its own tree, so a seek loop might record `pressesPerCheck` `[5, 3]`.

An entirely mapped body executes its interactions in order. If any instruction requires navigation generation, the complete ordered body is generated once per iteration; a mapped prefix is not executed separately. Generated-flow labels retain the `loop-NNN` form.

A failed flow, including a mapped command or an automatic scroll to find a named target, interrupts execution immediately. Remaining instructions do not execute, that body is not counted, and there is no post-body condition check. Results retain the previous condition's tier and reasoning separately from execution-error reasoning. Cancellation also interrupts immediately and propagates.

A valid negative inspector verdict leaves the condition unsatisfied. Navigator flow generation and scroll-direction requests share the inspector tree/visual request policy: each owns a 30-second timeout, and failed requests, truncated completions, empty replies or invalid response contracts are fatal model failures, and a backend protocol break is the separate fixed class `<stage> backend protocol failed`. Completion metadata is checked before decoding. Caller cancellation and an enclosing shorter deadline propagate rather than becoming model failures. The CLI stops subsequent journeys, retains completed journey results, writes `model_failure` for the affected journey and summary, and exits 5. Required result-write failure takes precedence with exit 3. Exhausting a loop with a valid negative condition is an ordinary journey failure: exit 4, with the usual suite continuation. See [run artifacts](run-artifacts.md).

Generated complete bodies are strict JSON action lists validated through the device-free compiler. The selected `ActionFlow` is rendered for an optional YAML artifact and executed directly. Entire mapped bodies are validated before their first direct key or other action. Malformed or unsupported model actions are model failures; local command preparation failures instead abort the suite with retained completed results, `setup_failure` for the affected journey and summary, and exit 3. Scroll replies accept only trimmed, case-insensitive `UP`, `DOWN`, `LEFT`, `RIGHT` or `NONE`. A valid `NONE` preserves ordinary navigation; request failure or an invalid direction stops before fallback interaction or later body work.

Inspector replies require a strict JSON object with a genuine boolean `passed` and string `reasoning`. Code fences, extra keys and an empty reasoning string are supported. Two optional fields are validated whenever present and used only for an unmet batched-loop check: `target`, either `"notVisible"` or a bounds string `[l,t][r,b]` with positive width and height, and `estimatedIterations`, a JSON integer of at least 1. Any other value, including a string number, a fraction or a boolean, is an invalid verdict. An explicit `null` counts as absent. Only batched-loop checks append a short paragraph to the tree request describing these fields; every other request is unchanged. Known truncation finish reasons (`length`, `max_tokens`, `incomplete`) fail before verdict decoding. Model-failure diagnostics contain only fixed stage and failure-class text, without raw replies, request exceptions or causes.

## Results, evidence and reuse

Loop segments include optional structured `loop` metadata: `condition`, completed `iterations`, `tier` (`literal`, `focus`, `tree`, `visual` or `precheck`) and final condition `reasoning`. When a pre-check answered the final check, `precheck` holds its `probability`, `model` and `host`, and the field is absent otherwise. The tier is `precheck` only when the pre-check decided alone; when the inspector confirmed or overruled a confident pass, `precheck.outcome` is `confirmed` or `overruled` and the tier is the inspector's (`tree` or `visual`). Batched D-pad loops also record `pressesPerCheck`, the bodies completed before each check after the first, for example `[6]` or `[3, 2, 1]`. It sums to `iterations` and is absent for other loops and when no body ran. Segment reasoning may also describe iteration or execution context. Tree and visual tiers retain the final evaluated hierarchy or screenshot reference when optional persistence succeeds. Failure to persist an optional screenshot uses a temporary current capture; it never permits visual evaluation without a captured image.

`ConditionEvaluator.evaluate` performs one action-free check. It does not poll, delay or accumulate history, so a caller can own an overall deadline. An optional per-call `InspectionContext` carries reference text and earlier screenshot paths, empty by default. References are labelled separately from current state and are not proof of the current condition. The CLI fills it with bounded earlier verdicts, the execution trail (including earlier iterations of the current loop) and, for `visually` conditions, earlier screenshots; see [journey memory](journey-memory.md).

## Entry points and ownership

YAML parsing and serialization share core's ordered body representation with execution and [dry-run preview](dry-run.md). Dry run previews one complete body and the authored condition and limit; it performs no capture or condition evaluation. JSON artifacts expose the metadata described above.

Semantic evaluation belongs to the CLI/agent path. Public MCP `run_loop` retains its literal text condition and raw key-press action, including its existing defaults and schema. `check_visible` and `check_focused` remain deterministic device tools; the external MCP caller owns semantic reasoning and credentials. MCP depends only on core/device, as required by [ADR-0001](../adr/0001-mcp-device-boundary.md).

The shared model-failure contract covers navigator and inspector calls during execution, plus slow-path dry-run action and complete-loop generation. Preview validates complete structured action lists and plans the whole suite before Markdown writing. A model failure exits 5 without a new partial successful report or normal result JSON; setup, local validation and required report-writing failures exit 3. Caller cancellation propagates without a completed report.

## Source and coverage

- [LoopStepInferrer](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/parser/LoopStepInferrer.kt), [FocusConditionParser](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/parser/FocusConditionParser.kt) and core parser/model tests cover authoring and grammar.
- [ConditionEvaluator](../../verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/ConditionEvaluator.kt) and [ConditionEvaluatorTest](../../verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/ConditionEvaluatorTest.kt) cover tier order, evidence and cancellation.
- [FocusStepPlanner](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/hierarchy/FocusStepPlanner.kt) and [FocusStepPlannerTest](../../verity/core/src/test/kotlin/me/chrisbanes/verity/core/hierarchy/FocusStepPlannerTest.kt) cover step counting and batch eligibility; [LoopBatchingTest](../../verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/LoopBatchingTest.kt) covers batches, fallbacks, the limit cap and unchanged ineligible loops.
- [InspectorAgentTest](../../verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/InspectorAgentTest.kt), [OrchestratorTest](../../verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt) and [RunCommandTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt) cover model failures, complete bodies and persisted outcomes using fake requests and devices.
