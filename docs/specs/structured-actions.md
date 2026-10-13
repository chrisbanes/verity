# Internal structured actions

CLI execution and dry-run planning share a complete `ActionFlow`: the requested app ID and an ordered list of typed `Interaction` values. Journey YAML remains the authored input format. Public MCP `run_flow` continues to accept a YAML string, and `get_context` continues to supply the Maestro YAML reference.

The navigator returns strict JSON with an `actions` array. Metadata is checked before decoding. The request supplies the app ID; a response cannot replace it. Unknown properties or action types, invalid regular expressions, unsupported keys and invalid waits fail before execution. The supported actions are key presses, raw Android keycodes, text/ID taps, text/focused long presses, literal text input, directional scroll/swipe, pull to refresh, default scroll, application launch, animation waits and positive visible waits. A visible wait requires a positive integer timeout and exactly one text or ID selector.

An unknown `keyPress` name fails with `UNSUPPORTED_KEY` and a detail that names the key and points to `keyCode`. The name is echoed only when it matches `[A-Za-z0-9 _.-]{1,64}`; otherwise the detail omits it. The navigator appends this detail to its otherwise fixed invalid-response diagnostic. Before failing, the navigator makes one bounded retry: it re-requests the flow with the validation detail and its previous response, and fails only if the retry is also invalid.

## Raw keycodes

`{"type":"keyCode","keycode":174}` sends a raw Android keycode, here `KEYCODE_BOOKMARK`. `keycode` is a required non-negative JSON integer and `longPress` an optional JSON boolean (default `false`); strings, fractions, `null` and unknown properties are rejected. On Android TV and mobile, the whole steps `press keycode N` and `long press keycode N` map to this action without a model call; other wording goes to the navigator, whose Android prompts teach the action.

A keycode action is never compiled to a Maestro command. It runs through `DeviceSession.pressKey(keycode, longPress)`, and needs `supportsRawKeycodes` (plus `supportsLongPress` when `longPress` is true). A session without them, currently iOS, rejects the flow with `UNSUPPORTED_INTERACTION` before any device work in that flow or loop body: mapped groups are checked before their first action, and generated flows right after generation, before the generated-flow artifact is written.

Execution splits an ordered list at its keycode actions. Each maximal run of other actions is sent as its own Maestro sub-flow through `executeActions`, keeping the app configuration preamble; each keycode is pressed directly between them and followed by the same animation settling as `keyPress`. The first failed sub-flow stops execution, and later actions do not run. A list without keycodes is sent unchanged as one flow. The rule applies to generated flows, generated loop bodies and mapped steps (which already run one interaction at a time). Rendered YAML shows a keycode as a Verity preview-only entry:

```yaml
- keyCode:
    keycode: 174
    longPress: false
```

The device-free compiler prepares the complete command list before any driver operation. Mapped groups also pass complete-list validation before their first direct key press. Preparation failures are safe local errors. Runtime failures keep the existing `FlowResult`, and cancellation propagates. Both Android and iOS retain their existing Maestro Orchestra and driver connections.

Generated actions exclude screenshots, assertions, scripts, nested flows, media, coordinates and arbitrary configuration. Those supplied-YAML capabilities remain at the public MCP boundary. Internal execution does not render, write or reparse YAML. It renders the selected list only when writing an optional generated-flow artifact or a dry-run preview; execution receives that same selected object.

Dry run remains device-free, defers provider creation until generation is required, and plans the whole suite before writing Markdown. Model response failures exit `5`; local preparation and required output failures exit `3`. Existing loop ordering, assertion handling, valid `NONE` scroll suggestions and suite stop/continuation rules remain in effect.

See [dry run](dry-run.md), [loop conditions](loop-conditions.md), and [run artifacts](run-artifacts.md).
