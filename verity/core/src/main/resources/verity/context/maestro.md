# Maestro YAML Reference

## Flow Structure

Every flow starts with an `appId` header:

```yaml
appId: com.example.app
---
- pressKey: Remote Dpad Down
- waitForAnimationToEnd
```

## Common Commands

### Key Presses
```yaml
- pressKey: Remote Dpad Down
- pressKey: back
```

### Wait for Animation
```yaml
- waitForAnimationToEnd
```
Always add after navigation actions.

### Extended Wait
```yaml
- extendedWaitUntil:
    visible: "Loading"
    timeout: 10000
```
Use when content needs time to load.

### Launch App
```yaml
- launchApp:
    appId: com.example.app
    clearState: false
```

### Tap (Mobile/Tablet)
```yaml
- tapOn:
    text: "Button Label"
```

### Scroll (Mobile/Tablet)
```yaml
- scroll
```

### Input Text
```yaml
- inputText: "search query"
```

### Assert Visible (in flows)
```yaml
- assertVisible:
    text: "Expected Text"
```

Note: For Verity journeys, prefer using `[?]` assertions in the journey file rather than Maestro's built-in assertions. Journey assertions are evaluated by the orchestrator with LLM support.
