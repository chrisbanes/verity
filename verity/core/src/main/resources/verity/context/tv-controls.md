# TV Remote Controls

## Android TV D-pad Mapping

| Action | Maestro Key Name |
|--------|-----------------|
| D-pad Up | `Remote Dpad Up` |
| D-pad Down | `Remote Dpad Down` |
| D-pad Left | `Remote Dpad Left` |
| D-pad Right | `Remote Dpad Right` |
| Select / Enter | `Remote Dpad Center` |
| Back | `back` |
| Home | `home` |
| Menu | `Remote Media Menu` |
| Play/Pause | `Remote Media Play Pause` |
| Rewind | `Remote Media Rewind` |
| Fast Forward | `Remote Media Fast Forward` |

## Maestro YAML for Key Presses

```yaml
- pressKey: Remote Dpad Down
- pressKey: Remote Dpad Center
- pressKey: back
```

## Raw Android Keys and Holds through MCP

Use named keys for common controls. For a vendor or remote key not recognized by
Maestro, call MCP `press_key` with a non-negative integer `keycode` instead of
`key`. For example, Android `KEYCODE_BOOKMARK` is `174`:

```json
{"session_id": "<open session ID>", "keycode": 174}
```

Supply exactly one of `key` or `keycode`. Add `"long_press": true` to hold either
a named key or a raw code; Android sends `input keyevent --longpress`. This flag
uses Android's hold behavior and does not accept a custom duration. iOS rejects
raw codes and holds before input. These are MCP arguments, not Maestro YAML
fields or journey-step syntax. The default MCP call waits for animation after
the press; optional focus waiting retains its separate tool behavior.

## Navigation Patterns

- **Row navigation**: D-pad Left/Right moves between items in a row
- **Vertical navigation**: D-pad Up/Down moves between rows
- **Content entry**: Select (D-pad Center) opens detail pages
- **Back navigation**: Back returns to previous screen

Always add `waitForAnimationToEnd` after navigation presses to allow transitions to complete.
