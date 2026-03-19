# InteractionMapper: Mobile Touch Navigation Fast Path

## Problem

The fast path is geared toward TV. `PlatformKeyMapper` maps instructions to d-pad key presses, which covers Android TV well but leaves mobile (phone/tablet) interactions entirely to the LLM slow path. Tapping a visible button, scrolling a list, or swiping to dismiss all require an LLM round-trip even though they could be resolved deterministically.

## Solution

Replace `PlatformKeyMapper` with `InteractionMapper` — a broader abstraction that maps natural-language instructions to deterministic device interactions, not just key presses.

## Core Abstraction

```kotlin
sealed interface Interaction {
    data class KeyPress(val keyName: String) : Interaction
    data class TapOnText(val text: String) : Interaction
    data class TapOnId(val resourceId: String) : Interaction
    data class Scroll(val direction: Direction) : Interaction
    data class Swipe(val direction: Direction) : Interaction
    data object LongPressOnFocused : Interaction
    data class LongPressOnText(val text: String) : Interaction
    data object PullToRefresh : Interaction
}

interface InteractionMapper {
    fun map(instruction: String): Interaction?
}
```

Platform implementations:
- `AndroidTvInteractionMapper` — mostly `KeyPress` (preserves today's behavior)
- `AndroidMobileInteractionMapper` — tap, scroll, swipe, long-press, pull-to-refresh, plus keys (back, home)
- `IosInteractionMapper` — same gesture set, iOS key subset (home, volume)

## Gesture Vocabulary

| Instruction pattern | Interaction | Maestro YAML |
|---|---|---|
| "tap [X]" / "press [X]" / "click [X]" | `TapOnText("X")` | `tapOn: {text: "X"}` |
| "tap on [id]" (resource ID) | `TapOnId("res_id")` | `tapOn: {id: "res_id"}` |
| "scroll down/up" | `Scroll(DOWN/UP)` | `scroll` with direction |
| "swipe left/right/up/down" | `Swipe(LEFT/RIGHT/...)` | `swipeOn: {direction: ...}` |
| "long press [X]" / "hold [X]" | `LongPressOnText("X")` | `longPressOn: {text: "X"}` |
| "pull to refresh" | `PullToRefresh` | `scroll` up from top |
| "press back" / "press home" | `KeyPress("back")` | `pressKey: back` |

Pattern matching uses simple keyword/regex — no LLM. Instructions like "navigate to Settings" or "find the trending section" do **not** match and fall through to the LLM slow path.

## Scroll-to-Find Loop

When `InteractionMapper` returns `TapOnText("Settings")` but the target isn't in the current accessibility tree:

1. Check the accessibility tree for the tap target
2. If found, tap deterministically — done
3. If not found, ask the LLM: "You're looking for [target]. The screen shows [rendered tree]. Which direction should I scroll?"
4. LLM responds with a scroll direction (or gives up)
5. Execute the scroll, wait for animation, go to step 1
6. Cap at ~5 iterations to avoid infinite loops

The LLM is only invoked when the target isn't immediately visible. If it's already on screen, the interaction stays deterministic and free.

## Vision Fallback

For unlabeled elements (custom views, games, WebViews), the hybrid approach:

1. `InteractionMapper.map(instruction)` returns an `Interaction`
2. Target not in tree, scroll-to-find loop exhausts attempts
3. Fall through to the LLM slow path — NavigatorAgent gets a screenshot + instruction and generates Maestro YAML

This preserves the cost hierarchy: deterministic (free) > small LLM call for scroll direction > full LLM call for complex navigation.

## Execution Flow

```
1. InteractionMapper.map(instruction) → Interaction?
2. If null → slow path (LLM NavigatorAgent, same as today)
3. If Interaction:
   a. If target is on screen → execute deterministically
   b. If target not on screen → scroll-to-find loop (LLM picks direction)
   c. If scroll-to-find gives up → fall through to slow path
```

## Changes to Existing Code

**New:**
- `InteractionMapper` interface + `Interaction` sealed type in `:verity:core`
- `AndroidTvInteractionMapper`, `AndroidMobileInteractionMapper`, `IosInteractionMapper`
- Scroll-to-find loop in `Orchestrator`

**Modified:**
- `Orchestrator.isFastPath()` — checks `InteractionMapper.map()` instead of `PlatformKeyMapper.map()`
- `Orchestrator` execution dispatch — handles all `Interaction` types, not just key presses

**Removed:**
- `PlatformKeyMapper` and its platform implementations (replaced by `InteractionMapper`)

**Unchanged:**
- Assertion system (VISIBLE/FOCUSED/TREE/VISUAL)
- NavigatorAgent (LLM slow path fallback)
- InspectorAgent (assertion evaluation)
- Journey parsing, segmentation
- DeviceSession interface
