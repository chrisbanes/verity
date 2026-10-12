# Structured action reference

Internal navigation returns a JSON object with an ordered `actions` array. The request supplies the app ID; do not add it to the response.

Each action has a `type` and only its supported fields:

- `keyPress`: `keyName`, a supported Maestro key such as `BACK`, `HOME`, or `Remote Dpad Down`. Only Maestro key names are accepted.
- `keyCode` (Android only; never use it on iOS): non-negative integer `keycode` and optional boolean `longPress`. It sends a raw Android keycode. Use it when a step names a keycode or the key is not in Maestro's set; for example, `KEYCODE_BOOKMARK` is `174`: `{"type":"keyCode","keycode":174}`.
- `tapOnText`, `longPressOnText`: `text`, a regular expression.
- `tapOnId`: `resourceId`, a regular expression.
- `inputText`: `text`, preserved literally, including whitespace.
- `scroll`, `swipe`: `direction`, one of `UP`, `DOWN`, `LEFT`, `RIGHT`.
- `longPressOnFocused`, `pullToRefresh`, `defaultScroll`: no additional fields.
- `launchApp`: optional boolean `clearState`.
- `waitForAnimation`: optional positive integer `timeoutMs`.
- `waitUntilVisible`: positive integer `timeoutMs` and exactly one of `text` or `resourceId`.

Add `waitForAnimation` after navigation. Use `waitUntilVisible` when content must appear before continuing. On Android TV, navigate with `Remote Dpad Up`, `Remote Dpad Down`, `Remote Dpad Left`, `Remote Dpad Right`, and `Remote Dpad Center`.

Example:

```json
{"actions":[{"type":"tapOnText","text":"Settings"},{"type":"waitForAnimation"},{"type":"waitUntilVisible","text":"Account","timeoutMs":3000}]}
```

Do not emit YAML, screenshots, assertions, scripts, coordinates, nested flows, configuration properties, or unknown action types. These remain capabilities of the public supplied-YAML interface, not generated internal actions.
