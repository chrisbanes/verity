# InteractionMapper Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace `PlatformKeyMapper` with `InteractionMapper` to expand the deterministic fast path from key presses to touch gestures (tap, scroll, swipe, long-press, pull-to-refresh), making Verity useful for mobile app testing.

**Architecture:** `InteractionMapper` maps natural-language instructions to an `Interaction` sealed type. The `Orchestrator` dispatches on interaction type — key presses call `session.pressKey()`, gestures generate single-command Maestro YAML via `session.executeFlow()`. A scroll-to-find loop handles off-screen tap targets using the accessibility tree + LLM-guided scroll direction.

**Tech Stack:** Kotlin, assertk, kotlinx-coroutines-test, Maestro YAML

---

### Task 1: Interaction sealed type and Direction enum

**Files:**
- Create: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/Interaction.kt`

**Step 1: Write the code**

```kotlin
package me.chrisbanes.verity.core.interaction

enum class Direction {
  UP, DOWN, LEFT, RIGHT,
}

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
```

**Step 2: Build to verify it compiles**

Run: `./gradlew :verity:core:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/Interaction.kt
git commit -m "feat: add Interaction sealed type and Direction enum"
```

---

### Task 2: InteractionMapper interface

**Files:**
- Create: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/InteractionMapper.kt`

**Step 1: Write the code**

```kotlin
package me.chrisbanes.verity.core.interaction

import me.chrisbanes.verity.core.model.Platform

interface InteractionMapper {
  fun map(instruction: String): Interaction?

  fun allMappable(instructions: List<String>): Boolean = instructions.all { map(it) != null }

  companion object {
    fun forPlatform(platform: Platform): InteractionMapper = when (platform) {
      Platform.ANDROID_TV -> AndroidTvInteractionMapper
      Platform.ANDROID_MOBILE -> AndroidMobileInteractionMapper
      Platform.IOS -> IosInteractionMapper
    }
  }
}
```

Note: This will not compile yet because the platform implementations don't exist. That's fine — they're created in the next tasks.

**Step 2: Commit** (no build check — depends on Tasks 3-5)

```bash
git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/InteractionMapper.kt
git commit -m "feat: add InteractionMapper interface"
```

---

### Task 3: AndroidTvInteractionMapper (preserves existing behavior)

**Files:**
- Create: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/AndroidTvInteractionMapper.kt`
- Create: `verity/core/src/test/kotlin/me/chrisbanes/verity/core/interaction/AndroidTvInteractionMapperTest.kt`

**Step 1: Write the failing test**

```kotlin
package me.chrisbanes.verity.core.interaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class AndroidTvInteractionMapperTest {

  private val mapper = AndroidTvInteractionMapper

  @Test
  fun `maps d-pad down to key press`() {
    assertThat(mapper.map("press d-pad down")).isEqualTo(Interaction.KeyPress("Remote Dpad Down"))
  }

  @Test
  fun `maps d-pad up to key press`() {
    assertThat(mapper.map("press d-pad up")).isEqualTo(Interaction.KeyPress("Remote Dpad Up"))
  }

  @Test
  fun `maps d-pad left to key press`() {
    assertThat(mapper.map("press d-pad left")).isEqualTo(Interaction.KeyPress("Remote Dpad Left"))
  }

  @Test
  fun `maps d-pad right to key press`() {
    assertThat(mapper.map("press d-pad right")).isEqualTo(Interaction.KeyPress("Remote Dpad Right"))
  }

  @Test
  fun `maps select to d-pad center`() {
    assertThat(mapper.map("press select")).isEqualTo(Interaction.KeyPress("Remote Dpad Center"))
  }

  @Test
  fun `maps d-pad center`() {
    assertThat(mapper.map("press d-pad center")).isEqualTo(Interaction.KeyPress("Remote Dpad Center"))
  }

  @Test
  fun `maps back`() {
    assertThat(mapper.map("press back")).isEqualTo(Interaction.KeyPress("back"))
  }

  @Test
  fun `maps home`() {
    assertThat(mapper.map("press home")).isEqualTo(Interaction.KeyPress("home"))
  }

  @Test
  fun `maps media keys`() {
    assertThat(mapper.map("press menu")).isEqualTo(Interaction.KeyPress("Remote Media Menu"))
    assertThat(mapper.map("press play")).isEqualTo(Interaction.KeyPress("Remote Media Play Pause"))
    assertThat(mapper.map("press rewind")).isEqualTo(Interaction.KeyPress("Remote Media Rewind"))
  }

  @Test
  fun `is case insensitive`() {
    assertThat(mapper.map("Press D-Pad Down")).isEqualTo(Interaction.KeyPress("Remote Dpad Down"))
  }

  @Test
  fun `returns null for unknown instruction`() {
    assertThat(mapper.map("navigate to settings")).isNull()
  }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :verity:core:test --tests "*.AndroidTvInteractionMapperTest" -x spotlessCheck`
Expected: Compilation failure — `AndroidTvInteractionMapper` doesn't exist

**Step 3: Write implementation**

```kotlin
package me.chrisbanes.verity.core.interaction

object AndroidTvInteractionMapper : InteractionMapper {

  private val KEY_MAP = mapOf(
    "press d-pad down" to "Remote Dpad Down",
    "press d-pad up" to "Remote Dpad Up",
    "press d-pad left" to "Remote Dpad Left",
    "press d-pad right" to "Remote Dpad Right",
    "press d-pad center" to "Remote Dpad Center",
    "press select" to "Remote Dpad Center",
    "press enter" to "Remote Dpad Center",
    "press back" to "back",
    "press home" to "home",
    "press menu" to "Remote Media Menu",
    "press play" to "Remote Media Play Pause",
    "press pause" to "Remote Media Play Pause",
    "press rewind" to "Remote Media Rewind",
    "press fast forward" to "Remote Media Fast Forward",
  )

  override fun map(instruction: String): Interaction? {
    val keyName = KEY_MAP[instruction.trim().lowercase()] ?: return null
    return Interaction.KeyPress(keyName)
  }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :verity:core:test --tests "*.AndroidTvInteractionMapperTest" -x spotlessCheck`
Expected: All tests pass

**Step 5: Commit**

```bash
git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/AndroidTvInteractionMapper.kt \
       verity/core/src/test/kotlin/me/chrisbanes/verity/core/interaction/AndroidTvInteractionMapperTest.kt
git commit -m "feat: add AndroidTvInteractionMapper"
```

---

### Task 4: AndroidMobileInteractionMapper (new touch gestures)

**Files:**
- Create: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/AndroidMobileInteractionMapper.kt`
- Create: `verity/core/src/test/kotlin/me/chrisbanes/verity/core/interaction/AndroidMobileInteractionMapperTest.kt`

**Step 1: Write the failing test**

```kotlin
package me.chrisbanes.verity.core.interaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class AndroidMobileInteractionMapperTest {

  private val mapper = AndroidMobileInteractionMapper

  // Key presses (preserved from old AndroidMobileKeyMapper)
  @Test
  fun `maps press back to key press`() {
    assertThat(mapper.map("press back")).isEqualTo(Interaction.KeyPress("back"))
  }

  @Test
  fun `maps press home to key press`() {
    assertThat(mapper.map("press home")).isEqualTo(Interaction.KeyPress("home"))
  }

  @Test
  fun `maps press enter to key press`() {
    assertThat(mapper.map("press enter")).isEqualTo(Interaction.KeyPress("enter"))
  }

  @Test
  fun `maps volume keys`() {
    assertThat(mapper.map("press volume up")).isEqualTo(Interaction.KeyPress("volume up"))
    assertThat(mapper.map("press volume down")).isEqualTo(Interaction.KeyPress("volume down"))
  }

  // Tap gestures
  @Test
  fun `maps tap on text`() {
    assertThat(mapper.map("tap Settings")).isEqualTo(Interaction.TapOnText("Settings"))
  }

  @Test
  fun `maps tap on with quotes`() {
    assertThat(mapper.map("tap \"Sign In\"")).isEqualTo(Interaction.TapOnText("Sign In"))
  }

  @Test
  fun `maps click as tap`() {
    assertThat(mapper.map("click Settings")).isEqualTo(Interaction.TapOnText("Settings"))
  }

  @Test
  fun `maps tap on as tap`() {
    assertThat(mapper.map("tap on Settings")).isEqualTo(Interaction.TapOnText("Settings"))
  }

  // Scroll gestures
  @Test
  fun `maps scroll down`() {
    assertThat(mapper.map("scroll down")).isEqualTo(Interaction.Scroll(Direction.DOWN))
  }

  @Test
  fun `maps scroll up`() {
    assertThat(mapper.map("scroll up")).isEqualTo(Interaction.Scroll(Direction.UP))
  }

  // Swipe gestures
  @Test
  fun `maps swipe left`() {
    assertThat(mapper.map("swipe left")).isEqualTo(Interaction.Swipe(Direction.LEFT))
  }

  @Test
  fun `maps swipe right`() {
    assertThat(mapper.map("swipe right")).isEqualTo(Interaction.Swipe(Direction.RIGHT))
  }

  @Test
  fun `maps swipe up`() {
    assertThat(mapper.map("swipe up")).isEqualTo(Interaction.Swipe(Direction.UP))
  }

  @Test
  fun `maps swipe down`() {
    assertThat(mapper.map("swipe down")).isEqualTo(Interaction.Swipe(Direction.DOWN))
  }

  // Long press
  @Test
  fun `maps long press on text`() {
    assertThat(mapper.map("long press Settings")).isEqualTo(Interaction.LongPressOnText("Settings"))
  }

  @Test
  fun `maps hold as long press`() {
    assertThat(mapper.map("hold Settings")).isEqualTo(Interaction.LongPressOnText("Settings"))
  }

  // Pull to refresh
  @Test
  fun `maps pull to refresh`() {
    assertThat(mapper.map("pull to refresh")).isEqualTo(Interaction.PullToRefresh)
  }

  // Case insensitivity
  @Test
  fun `is case insensitive`() {
    assertThat(mapper.map("Tap Settings")).isEqualTo(Interaction.TapOnText("Settings"))
    assertThat(mapper.map("SCROLL DOWN")).isEqualTo(Interaction.Scroll(Direction.DOWN))
  }

  // Unmappable
  @Test
  fun `returns null for complex instructions`() {
    assertThat(mapper.map("navigate to the settings page")).isNull()
    assertThat(mapper.map("find trending section")).isNull()
  }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :verity:core:test --tests "*.AndroidMobileInteractionMapperTest" -x spotlessCheck`
Expected: Compilation failure

**Step 3: Write implementation**

```kotlin
package me.chrisbanes.verity.core.interaction

object AndroidMobileInteractionMapper : InteractionMapper {

  private val KEY_MAP = mapOf(
    "press back" to "back",
    "press home" to "home",
    "press enter" to "enter",
    "press volume up" to "volume up",
    "press volume down" to "volume down",
  )

  private val SCROLL_DIRECTIONS = mapOf(
    "up" to Direction.UP,
    "down" to Direction.DOWN,
  )

  private val SWIPE_DIRECTIONS = mapOf(
    "up" to Direction.UP,
    "down" to Direction.DOWN,
    "left" to Direction.LEFT,
    "right" to Direction.RIGHT,
  )

  // Patterns tried in order. First match wins.
  // Each returns an Interaction? given the normalized (trimmed, lowercased) instruction.
  private val PATTERNS: List<(String) -> Interaction?> = listOf(
    // Key presses — exact match
    { instruction -> KEY_MAP[instruction]?.let { Interaction.KeyPress(it) } },

    // Pull to refresh — exact match
    { instruction -> if (instruction == "pull to refresh") Interaction.PullToRefresh else null },

    // Scroll direction
    { instruction ->
      if (instruction.startsWith("scroll ")) {
        val dir = instruction.removePrefix("scroll ")
        SCROLL_DIRECTIONS[dir]?.let { Interaction.Scroll(it) }
      } else null
    },

    // Swipe direction
    { instruction ->
      if (instruction.startsWith("swipe ")) {
        val dir = instruction.removePrefix("swipe ")
        SWIPE_DIRECTIONS[dir]?.let { Interaction.Swipe(it) }
      } else null
    },

    // Long press / hold
    { instruction ->
      val text = instruction.removePrefix("long press on ")
        .takeIf { it != instruction }
        ?: instruction.removePrefix("long press ")
          .takeIf { it != instruction }
        ?: instruction.removePrefix("hold ")
          .takeIf { it != instruction }
      text?.let { Interaction.LongPressOnText(extractText(it)) }
    },

    // Tap / click / tap on
    { instruction ->
      val text = instruction.removePrefix("tap on ")
        .takeIf { it != instruction }
        ?: instruction.removePrefix("tap ")
          .takeIf { it != instruction }
        ?: instruction.removePrefix("click on ")
          .takeIf { it != instruction }
        ?: instruction.removePrefix("click ")
          .takeIf { it != instruction }
      text?.let { Interaction.TapOnText(extractText(it)) }
    },
  )

  override fun map(instruction: String): Interaction? {
    val normalized = instruction.trim().lowercase()
    for (pattern in PATTERNS) {
      pattern(normalized)?.let { return it }
    }
    return null
  }
}

/** Strip surrounding quotes from extracted text if present. */
private fun extractText(raw: String): String {
  val trimmed = raw.trim()
  if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
    return trimmed.substring(1, trimmed.length - 1)
  }
  return trimmed
}
```

**Important note on case:** The `map()` function lowercases the instruction for pattern matching, but tap/long-press extract the target text from the **lowercased** string. This means `TapOnText("settings")` not `TapOnText("Settings")`. The tests above reflect the original casing. **Decision needed:** should we preserve original casing for the target text? If yes, we need to match against lowercase but extract from the original. Update both impl and tests accordingly. For now, match the test expectations — use original-cased instruction for text extraction:

```kotlin
override fun map(instruction: String): Interaction? {
  val normalized = instruction.trim()
  val lower = normalized.lowercase()
  for (pattern in PATTERNS) {
    pattern(lower, normalized)?.let { return it }
  }
  return null
}
```

And update each pattern lambda signature to `(String, String) -> Interaction?` where the first arg is lowercased (for matching) and the second is original (for text extraction). The tap pattern becomes:

```kotlin
{ lower, original ->
  val prefix = listOf("tap on ", "tap ", "click on ", "click ")
    .firstOrNull { lower.startsWith(it) }
  prefix?.let {
    Interaction.TapOnText(extractText(original.trim().substring(it.length)))
  }
},
```

Apply the same dual-arg pattern to long press and hold.

**Step 4: Run test to verify it passes**

Run: `./gradlew :verity:core:test --tests "*.AndroidMobileInteractionMapperTest" -x spotlessCheck`
Expected: All tests pass

**Step 5: Commit**

```bash
git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/AndroidMobileInteractionMapper.kt \
       verity/core/src/test/kotlin/me/chrisbanes/verity/core/interaction/AndroidMobileInteractionMapperTest.kt
git commit -m "feat: add AndroidMobileInteractionMapper with touch gestures"
```

---

### Task 5: IosInteractionMapper

**Files:**
- Create: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/IosInteractionMapper.kt`
- Create: `verity/core/src/test/kotlin/me/chrisbanes/verity/core/interaction/IosInteractionMapperTest.kt`

**Step 1: Write the failing test**

Same gesture tests as AndroidMobileInteractionMapper, but key map uses iOS keys (home, lock, volume up/down — no back, no enter).

```kotlin
package me.chrisbanes.verity.core.interaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class IosInteractionMapperTest {

  private val mapper = IosInteractionMapper

  @Test
  fun `maps press home`() {
    assertThat(mapper.map("press home")).isEqualTo(Interaction.KeyPress("home"))
  }

  @Test
  fun `maps press lock`() {
    assertThat(mapper.map("press lock")).isEqualTo(Interaction.KeyPress("lock"))
  }

  @Test
  fun `maps volume keys`() {
    assertThat(mapper.map("press volume up")).isEqualTo(Interaction.KeyPress("volume up"))
    assertThat(mapper.map("press volume down")).isEqualTo(Interaction.KeyPress("volume down"))
  }

  @Test
  fun `does not map press back`() {
    // iOS has no hardware back button — this should NOT be a key press
    assertThat(mapper.map("press back")).isNull()
  }

  @Test
  fun `maps tap on text`() {
    assertThat(mapper.map("tap Settings")).isEqualTo(Interaction.TapOnText("Settings"))
  }

  @Test
  fun `maps scroll down`() {
    assertThat(mapper.map("scroll down")).isEqualTo(Interaction.Scroll(Direction.DOWN))
  }

  @Test
  fun `maps swipe left`() {
    assertThat(mapper.map("swipe left")).isEqualTo(Interaction.Swipe(Direction.LEFT))
  }

  @Test
  fun `maps long press`() {
    assertThat(mapper.map("long press Photos")).isEqualTo(Interaction.LongPressOnText("Photos"))
  }

  @Test
  fun `maps pull to refresh`() {
    assertThat(mapper.map("pull to refresh")).isEqualTo(Interaction.PullToRefresh)
  }

  @Test
  fun `returns null for complex instructions`() {
    assertThat(mapper.map("navigate to settings")).isNull()
  }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :verity:core:test --tests "*.IosInteractionMapperTest" -x spotlessCheck`
Expected: Compilation failure

**Step 3: Write implementation**

The iOS mapper shares most gesture logic with Android mobile. Extract the shared touch-gesture matching into a helper or simply duplicate (YAGNI — three small objects is fine). The only difference is the key map:

```kotlin
package me.chrisbanes.verity.core.interaction

object IosInteractionMapper : InteractionMapper {

  private val KEY_MAP = mapOf(
    "press home" to "home",
    "press lock" to "lock",
    "press volume up" to "volume up",
    "press volume down" to "volume down",
  )

  override fun map(instruction: String): Interaction? {
    return TouchInteractionParser.parse(instruction, KEY_MAP)
  }
}
```

To avoid duplicating the pattern-matching logic, extract a `TouchInteractionParser` internal helper:

Create: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/TouchInteractionParser.kt`

```kotlin
package me.chrisbanes.verity.core.interaction

/**
 * Shared parser for touch-based platforms (Android Mobile, iOS).
 * Each platform provides its own key map; gesture patterns are shared.
 */
internal object TouchInteractionParser {

  private val SCROLL_DIRECTIONS = mapOf(
    "up" to Direction.UP,
    "down" to Direction.DOWN,
  )

  private val SWIPE_DIRECTIONS = mapOf(
    "up" to Direction.UP,
    "down" to Direction.DOWN,
    "left" to Direction.LEFT,
    "right" to Direction.RIGHT,
  )

  private val TAP_PREFIXES = listOf("tap on ", "tap ", "click on ", "click ")
  private val LONG_PRESS_PREFIXES = listOf("long press on ", "long press ", "hold ")

  fun parse(instruction: String, keyMap: Map<String, String>): Interaction? {
    val normalized = instruction.trim()
    val lower = normalized.lowercase()

    // Key press — exact match
    keyMap[lower]?.let { return Interaction.KeyPress(it) }

    // Pull to refresh
    if (lower == "pull to refresh") return Interaction.PullToRefresh

    // Scroll
    if (lower.startsWith("scroll ")) {
      val dir = lower.removePrefix("scroll ")
      SCROLL_DIRECTIONS[dir]?.let { return Interaction.Scroll(it) }
    }

    // Swipe
    if (lower.startsWith("swipe ")) {
      val dir = lower.removePrefix("swipe ")
      SWIPE_DIRECTIONS[dir]?.let { return Interaction.Swipe(it) }
    }

    // Long press / hold
    LONG_PRESS_PREFIXES.firstOrNull { lower.startsWith(it) }?.let { prefix ->
      val text = extractText(normalized.substring(prefix.length))
      return Interaction.LongPressOnText(text)
    }

    // Tap / click
    TAP_PREFIXES.firstOrNull { lower.startsWith(it) }?.let { prefix ->
      val text = extractText(normalized.substring(prefix.length))
      return Interaction.TapOnText(text)
    }

    return null
  }

  private fun extractText(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
      return trimmed.substring(1, trimmed.length - 1)
    }
    return trimmed
  }
}
```

Then refactor `AndroidMobileInteractionMapper` to use `TouchInteractionParser.parse(instruction, KEY_MAP)` as well.

**Step 4: Run all interaction mapper tests**

Run: `./gradlew :verity:core:test --tests "*.interaction.*" -x spotlessCheck`
Expected: All tests pass

**Step 5: Run full core checks**

Run: `./gradlew :verity:core:test -x spotlessCheck`
Expected: All tests pass (including old PlatformKeyMapperTest, which still compiles independently)

**Step 6: Commit**

```bash
git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/TouchInteractionParser.kt \
       verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/IosInteractionMapper.kt \
       verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/AndroidMobileInteractionMapper.kt \
       verity/core/src/test/kotlin/me/chrisbanes/verity/core/interaction/IosInteractionMapperTest.kt
git commit -m "feat: add IosInteractionMapper and extract TouchInteractionParser"
```

---

### Task 6: InteractionExecutor — convert Interactions to device commands

**Files:**
- Create: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/InteractionExecutor.kt`
- Create: `verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/InteractionExecutorTest.kt`

This is the bridge between `Interaction` types and `DeviceSession` calls. It generates and executes Maestro YAML for touch gestures and calls `pressKey()` for key presses.

**Step 1: Write the failing test**

```kotlin
package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.interaction.Interaction

class InteractionExecutorTest {

  @Test
  fun `key press calls pressKey`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.KeyPress("back"))
    assertThat(session.pressedKeys).isEqualTo(listOf("back"))
  }

  @Test
  fun `tap on text generates tapOn flow`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.TapOnText("Settings"))
    assertThat(session.executedFlows.single()).isEqualTo("- tapOn:\n    text: \"Settings\"")
  }

  @Test
  fun `tap on id generates tapOn id flow`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.TapOnId("settings_btn"))
    assertThat(session.executedFlows.single()).isEqualTo("- tapOn:\n    id: \"settings_btn\"")
  }

  @Test
  fun `scroll generates scroll flow`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.Scroll(Direction.DOWN))
    assertThat(session.executedFlows.single()).isEqualTo("- scroll:\n    direction: DOWN")
  }

  @Test
  fun `swipe generates swipe flow`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.Swipe(Direction.LEFT))
    assertThat(session.executedFlows.single()).isEqualTo("- swipe:\n    direction: LEFT")
  }

  @Test
  fun `long press on text generates longPressOn flow`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.LongPressOnText("Photo"))
    assertThat(session.executedFlows.single()).isEqualTo("- longPressOn:\n    text: \"Photo\"")
  }

  @Test
  fun `pull to refresh generates scroll up`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.PullToRefresh)
    assertThat(session.executedFlows.single()).isEqualTo("- scroll:\n    direction: UP")
  }

  @Test
  fun `waits for animation after each interaction`() = runTest {
    val session = RecordingDeviceSession()
    val executor = InteractionExecutor(session)
    executor.execute(Interaction.KeyPress("back"))
    assertThat(session.waitCount).isEqualTo(1)
    executor.execute(Interaction.TapOnText("OK"))
    assertThat(session.waitCount).isEqualTo(2)
  }
}
```

Note: `RecordingDeviceSession` is a test helper — either reuse `FakeDeviceSession` from `OrchestratorTest` or create a focused one here. Use the existing pattern from OrchestratorTest's `FakeDeviceSession` but add fields for `pressedKeys`, `executedFlows`, and `waitCount`.

**Step 2: Run test to verify it fails**

Run: `./gradlew :verity:agent:test --tests "*.InteractionExecutorTest" -x spotlessCheck`
Expected: Compilation failure

**Step 3: Write implementation**

```kotlin
package me.chrisbanes.verity.agent

import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.device.DeviceSession

class InteractionExecutor(private val session: DeviceSession) {

  suspend fun execute(interaction: Interaction) {
    when (interaction) {
      is Interaction.KeyPress -> session.pressKey(interaction.keyName)
      is Interaction.TapOnText -> session.executeFlow("- tapOn:\n    text: \"${interaction.text}\"")
      is Interaction.TapOnId -> session.executeFlow("- tapOn:\n    id: \"${interaction.resourceId}\"")
      is Interaction.Scroll -> session.executeFlow("- scroll:\n    direction: ${interaction.direction}")
      is Interaction.Swipe -> session.executeFlow("- swipe:\n    direction: ${interaction.direction}")
      is Interaction.LongPressOnFocused -> session.executeFlow("- longPressOn:\n    focused: true")
      is Interaction.LongPressOnText -> session.executeFlow("- longPressOn:\n    text: \"${interaction.text}\"")
      Interaction.PullToRefresh -> session.executeFlow("- scroll:\n    direction: UP")
    }
    session.waitForAnimationToEnd()
  }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :verity:agent:test --tests "*.InteractionExecutorTest" -x spotlessCheck`
Expected: All tests pass

**Step 5: Commit**

```bash
git add verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/InteractionExecutor.kt \
       verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/InteractionExecutorTest.kt
git commit -m "feat: add InteractionExecutor for Interaction-to-device dispatch"
```

---

### Task 7: Migrate Orchestrator from PlatformKeyMapper to InteractionMapper

**Files:**
- Modify: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt`
- Modify: `verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt`

This is the core integration task. The Orchestrator's `isFastPath()`, `executeFastPath()`, and `executeLoop()` all switch from `PlatformKeyMapper` to `InteractionMapper` + `InteractionExecutor`.

**Step 1: Update the Orchestrator**

Changes to `Orchestrator.kt`:

1. Replace `import ...keymap.PlatformKeyMapper` with `import ...interaction.InteractionMapper` and `import ...interaction.Interaction`
2. Update `isFastPath()`:
   ```kotlin
   companion object {
     fun isFastPath(instructions: List<String>, platform: Platform): Boolean {
       val mapper = InteractionMapper.forPlatform(platform)
       return mapper.allMappable(instructions)
     }
   }
   ```
3. Update `executeFastPath()`:
   ```kotlin
   private suspend fun executeFastPath(instructions: List<String>, platform: Platform) {
     val mapper = InteractionMapper.forPlatform(platform)
     val executor = InteractionExecutor(session)
     for (instruction in instructions) {
       val interaction = checkNotNull(mapper.map(instruction)) {
         "Fast-path instruction '$instruction' did not map to an interaction for $platform"
       }
       executor.execute(interaction)
     }
   }
   ```
   Note: `executor.execute()` already calls `waitForAnimationToEnd()`, so remove the separate call.
4. Update `executeLoop()` to use `InteractionMapper` + `InteractionExecutor` instead of `PlatformKeyMapper` + `session.pressKey()`:
   ```kotlin
   private suspend fun executeLoop(...) {
     val mapper = InteractionMapper.forPlatform(platform)
     val executor = InteractionExecutor(session)
     val interaction = mapper.map(action)
     // ... rest uses executor.execute(interaction) instead of session.pressKey(keyName)
   }
   ```

**Step 2: Update OrchestratorTest**

The existing tests should still pass since `InteractionMapper` for Android TV maps the same instructions. Update:
- `isFastPath` tests don't change (same inputs, same results)
- Verify that mobile-specific instructions now classify as fast path:
  ```kotlin
  @Test
  fun `classifies tap instruction as fast path on mobile`() {
    val actions = listOf("tap Settings")
    val isFastPath = Orchestrator.isFastPath(actions, Platform.ANDROID_MOBILE)
    assertThat(isFastPath).isTrue()
  }

  @Test
  fun `classifies scroll instruction as fast path on mobile`() {
    val actions = listOf("scroll down", "tap OK")
    val isFastPath = Orchestrator.isFastPath(actions, Platform.ANDROID_MOBILE)
    assertThat(isFastPath).isTrue()
  }
  ```

**Step 3: Run all agent tests**

Run: `./gradlew :verity:agent:test -x spotlessCheck`
Expected: All tests pass

**Step 4: Commit**

```bash
git add verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt \
       verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt
git commit -m "feat: migrate Orchestrator to InteractionMapper"
```

---

### Task 8: Scroll-to-find loop for off-screen tap targets

**Files:**
- Modify: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt`
- Modify: `verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt`

This adds the scroll-to-find behavior: when a `TapOnText` or `TapOnId` targets an element not in the current accessibility tree, the orchestrator asks the LLM which direction to scroll.

**Step 1: Write the failing test**

```kotlin
@Test
fun `scroll-to-find taps target after scrolling`() = runTest {
  // First tree check: target not visible. After scroll: target visible.
  val session = FakeDeviceSession(
    containsTextResults = ArrayDeque(listOf(false, true)),
  )
  var scrollDirectionAsked = false
  val orchestrator = Orchestrator(
    session = session,
    navigatorFactory = {
      NavigatorAgent("unused") { _ ->
        FakeTextAgent {
          scrollDirectionAsked = true
          "DOWN" // LLM says scroll down
        }
      }
    },
    inspectorFactory = { /* unused */ },
  )

  val journey = Journey(
    name = "scroll-to-find",
    app = "com.example.app",
    platform = Platform.ANDROID_MOBILE,
    steps = listOf(JourneyStep.Action(instruction = "tap Settings")),
  )

  val result = orchestrator.run(journey)

  assertThat(result.passed).isTrue()
  assertThat(scrollDirectionAsked).isTrue()
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :verity:agent:test --tests "*.OrchestratorTest.scroll-to-find*" -x spotlessCheck`
Expected: FAIL — no scroll-to-find logic exists yet

**Step 3: Implement scroll-to-find in Orchestrator**

Add a private method to `Orchestrator`:

```kotlin
private suspend fun executeWithScrollToFind(
  interaction: Interaction,
  navigator: NavigatorAgent,
  platform: Platform,
  appId: String,
) {
  val executor = InteractionExecutor(session)

  // For interactions that don't target an element, just execute
  val targetText = when (interaction) {
    is Interaction.TapOnText -> interaction.text
    is Interaction.TapOnId -> interaction.resourceId
    is Interaction.LongPressOnText -> interaction.text
    else -> {
      executor.execute(interaction)
      return
    }
  }

  // Check if target is already on screen
  if (session.containsText(targetText)) {
    executor.execute(interaction)
    return
  }

  // Scroll-to-find loop (max 5 attempts)
  repeat(5) {
    val hierarchy = session.captureHierarchy()
    val direction = navigator.suggestScrollDirection(targetText, hierarchy)
      ?: return@repeat // LLM gave up

    executor.execute(Interaction.Scroll(direction))

    if (session.containsText(targetText)) {
      executor.execute(interaction)
      return
    }
  }

  // Fall through: execute anyway (will fail if element isn't there,
  // but Maestro may find it via its own matching)
  executor.execute(interaction)
}
```

This also requires a new method on `NavigatorAgent`:

```kotlin
suspend fun suggestScrollDirection(target: String, hierarchy: String): Direction? {
  // Small LLM call: "target not visible, here's the tree, which direction?"
  // Returns UP, DOWN, LEFT, RIGHT, or null if it gives up
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :verity:agent:test --tests "*.OrchestratorTest" -x spotlessCheck`
Expected: All tests pass

**Step 5: Commit**

```bash
git add verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/Orchestrator.kt \
       verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/NavigatorAgent.kt \
       verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/OrchestratorTest.kt
git commit -m "feat: add scroll-to-find loop for off-screen tap targets"
```

---

### Task 9: Delete PlatformKeyMapper and old tests

**Files:**
- Delete: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/keymap/PlatformKeyMapper.kt`
- Delete: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/keymap/AndroidTvKeyMapper.kt`
- Delete: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/keymap/AndroidMobileKeyMapper.kt`
- Delete: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/keymap/IosKeyMapper.kt`
- Delete: `verity/core/src/test/kotlin/me/chrisbanes/verity/core/keymap/PlatformKeyMapperTest.kt`

**Step 1: Verify no remaining references**

Run: `fd -t f . verity/ | xargs grep -l 'PlatformKeyMapper\|keymap\.' --include='*.kt' 2>/dev/null`

If any references remain, update them first.

**Step 2: Delete the files**

```bash
rm verity/core/src/main/kotlin/me/chrisbanes/verity/core/keymap/PlatformKeyMapper.kt \
   verity/core/src/main/kotlin/me/chrisbanes/verity/core/keymap/AndroidTvKeyMapper.kt \
   verity/core/src/main/kotlin/me/chrisbanes/verity/core/keymap/AndroidMobileKeyMapper.kt \
   verity/core/src/main/kotlin/me/chrisbanes/verity/core/keymap/IosKeyMapper.kt \
   verity/core/src/test/kotlin/me/chrisbanes/verity/core/keymap/PlatformKeyMapperTest.kt
```

**Step 3: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL — all tests pass, spotless clean

**Step 4: Commit**

```bash
git add -A
git commit -m "refactor: remove PlatformKeyMapper (replaced by InteractionMapper)"
```

---

### Task 10: Final verification

**Step 1: Run full check from clean state**

Run: `./gradlew clean check`
Expected: BUILD SUCCESSFUL

**Step 2: Run spotless**

Run: `./gradlew spotlessApply`

**Step 3: Commit any formatting fixes**

```bash
git add -A
git commit -m "style: apply spotless formatting"
```
