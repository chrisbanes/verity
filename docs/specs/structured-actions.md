# Internal structured actions

CLI execution and dry-run planning share a complete `ActionFlow`: the requested app ID and an ordered list of typed `Interaction` values. Journey YAML remains the authored input format. Public MCP `run_flow` continues to accept a YAML string, and `get_context` continues to supply the Maestro YAML reference.

The navigator returns strict JSON with an `actions` array. Metadata is checked before decoding. The request supplies the app ID; a response cannot replace it. Unknown properties or action types, invalid regular expressions, unsupported keys and invalid waits fail before execution. The supported actions are key presses, text/ID taps, text/focused long presses, literal text input, directional scroll/swipe, pull to refresh, default scroll, application launch, animation waits and positive visible waits. A visible wait requires a positive integer timeout and exactly one text or ID selector.

The device-free compiler prepares the complete command list before any driver operation. Mapped groups also pass complete-list validation before their first direct key press. Preparation failures are safe local errors. Runtime failures keep the existing `FlowResult`, and cancellation propagates. Both Android and iOS retain their existing Maestro Orchestra and driver connections.

Generated actions exclude screenshots, assertions, scripts, nested flows, media, coordinates and arbitrary configuration. Those supplied-YAML capabilities remain at the public MCP boundary. Internal execution does not render, write or reparse YAML. It renders the selected list only when writing an optional generated-flow artifact or a dry-run preview; execution receives that same selected object.

Dry run remains device-free, defers provider creation until generation is required, and plans the whole suite before writing Markdown. Model response failures exit `5`; local preparation and required output failures exit `3`. Existing loop ordering, assertion handling, valid `NONE` scroll suggestions and suite stop/continuation rules remain in effect.

See [dry run](dry-run.md), [loop conditions](loop-conditions.md), and [run artifacts](run-artifacts.md).
