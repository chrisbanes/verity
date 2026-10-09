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

## One current-state check

A case-insensitive leading word `visually` selects screenshot inspection. The prefix is removed before inspection, and a current screenshot is required. Text, focus and tree checks cannot satisfy this condition. Use this for appearance that accessibility text cannot establish, such as colour or a rendered image. Screenshot inspection uses the configured inspector model.

For other conditions, evaluation proceeds in this order:

| Tier | Check | Result |
| --- | --- | --- |
| `literal` | Case-insensitive substring match for the complete condition | A match satisfies the condition without a model request |
| `focus` | A recognised focus form, using the existing lenient focus detector | True or false is final for this check, without a model request |
| `tree` | Inspector evaluation of the current CONTENT accessibility hierarchy | A valid inspector verdict supplies the outcome and reasoning |

The focus grammar recognises exactly `<target> is focused`, `<target> has focus` and `focus is on <target>`, case-insensitively. Targets are trimmed, with matching surrounding single or double quotes removed; blank targets are not recognised. Other focus-like prose belongs to tree inspection. Seeing the target text alone does not satisfy a false focus condition: the literal tier searches for the complete condition, not the extracted target.

Choose a literal condition for visible text, a recognised focus form for navigation focus, tree inspection for relationships described by the accessibility hierarchy, and `visually` for appearance. Inspector tiers can incur provider costs; deterministic tiers do not request a model.

## Body boundaries and failure

The orchestrator checks immediately, then executes up to the permitted number of complete bodies. It checks once after each completed body, including the final permitted body. It never checks or stops because of the condition midway through a body. The iteration count increases only after every instruction in the body succeeds.

An entirely mapped body executes its interactions in order. If any instruction requires navigation generation, the complete ordered body is generated once per iteration; a mapped prefix is not executed separately. Generated-flow labels retain the `loop-NNN` form.

A failed flow, including a mapped command or an automatic scroll to find a named target, interrupts execution immediately. Remaining instructions do not execute, that body is not counted, and there is no post-body condition check. Results retain the previous condition's tier and reasoning separately from execution-error reasoning. Cancellation also interrupts immediately and propagates.

A valid negative inspector verdict leaves the condition unsatisfied. Navigator flow generation and scroll-direction requests share the inspector tree/visual request policy: each owns a 30-second timeout, and failed requests, truncated completions, empty replies or invalid response contracts are fatal model failures. Completion metadata is checked before decoding. Caller cancellation and an enclosing shorter deadline propagate rather than becoming model failures. The CLI stops subsequent journeys, retains completed journey results, writes `model_failure` for the affected journey and summary, and exits 5. Required result-write failure takes precedence with exit 3. Exhausting a loop with a valid negative condition is an ordinary journey failure: exit 4, with the usual suite continuation. See [run artifacts](run-artifacts.md).

Generated complete bodies are strict JSON action lists validated through the device-free compiler. The selected `ActionFlow` is rendered for an optional YAML artifact and executed directly. Entire mapped bodies are validated before their first direct key or other action. Malformed or unsupported model actions are model failures; local command preparation failures instead abort the suite with retained completed results, `setup_failure` for the affected journey and summary, and exit 3. Scroll replies accept only trimmed, case-insensitive `UP`, `DOWN`, `LEFT`, `RIGHT` or `NONE`. A valid `NONE` preserves ordinary navigation; request failure or an invalid direction stops before fallback interaction or later body work.

Inspector replies require a strict JSON object with a genuine boolean `passed` and string `reasoning`. Code fences, extra keys and an empty reasoning string are supported. Known truncation finish reasons (`length`, `max_tokens`, `incomplete`) fail before verdict decoding. Model-failure diagnostics contain only fixed stage and failure-class text, without raw replies, request exceptions or causes.

## Results, evidence and reuse

Loop segments include optional structured `loop` metadata: `condition`, completed `iterations`, `tier` (`literal`, `focus`, `tree` or `visual`) and final condition `reasoning`. Segment reasoning may also describe iteration or execution context. Tree and visual tiers retain the final evaluated hierarchy or screenshot reference when optional persistence succeeds. Failure to persist an optional screenshot uses a temporary current capture; it never permits visual evaluation without a captured image.

`ConditionEvaluator.evaluate` performs one action-free check. It does not poll, delay or accumulate history, so a caller can own an overall deadline. An optional per-call `InspectionContext` carries reference text and earlier screenshot paths, empty by default. References are labelled separately from current state and are not proof of the current condition. The CLI fills it with bounded earlier verdicts, the execution trail (including earlier iterations of the current loop) and, for `visually` conditions, earlier screenshots; see [journey memory](journey-memory.md).

## Entry points and ownership

YAML parsing and serialization share core's ordered body representation with execution and [dry-run preview](dry-run.md). Dry run previews one complete body and the authored condition and limit; it performs no capture or condition evaluation. JSON artifacts expose the metadata described above.

Semantic evaluation belongs to the CLI/agent path. Public MCP `run_loop` retains its literal text condition and raw key-press action, including its existing defaults and schema. `check_visible` and `check_focused` remain deterministic device tools; the external MCP caller owns semantic reasoning and credentials. MCP depends only on core/device, as required by [ADR-0001](../adr/0001-mcp-device-boundary.md).

The shared model-failure contract covers navigator and inspector calls during execution, plus slow-path dry-run action and complete-loop generation. Preview validates complete structured action lists and plans the whole suite before Markdown writing. A model failure exits 5 without a new partial successful report or normal result JSON; setup, local validation and required report-writing failures exit 3. Caller cancellation propagates without a completed report.

## Source and coverage

- [LoopStepInferrer](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/parser/LoopStepInferrer.kt), [FocusConditionParser](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/parser/FocusConditionParser.kt) and core parser/model tests cover authoring and grammar.
- [ConditionEvaluator](../../verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/ConditionEvaluator.kt) and [ConditionEvaluatorTest](../../verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/ConditionEvaluatorTest.kt) cover tier order, evidence and cancellation.
- [InspectorAgentTest](../../verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/InspectorAgentTest.kt), [OrchestratorTest](../../verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt) and [RunCommandTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandTest.kt) cover model failures, complete bodies and persisted outcomes using fake requests and devices.
