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

## Navigation Patterns

- **Row navigation**: D-pad Left/Right moves between items in a row
- **Vertical navigation**: D-pad Up/Down moves between rows
- **Content entry**: Select (D-pad Center) opens detail pages
- **Back navigation**: Back returns to previous screen

Always add `waitForAnimationToEnd` after navigation presses to allow transitions to complete.
