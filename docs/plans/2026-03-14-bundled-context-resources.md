# Bundled Context as Classpath Resources — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move bundled default context (Maestro reference, TV controls) from a hardcoded CLI string into `:verity:core` classpath resources so both CLI and MCP entry points share the same rich defaults.

**Architecture:** The canonical Maestro and TV-controls content lives as `.md` files in `core/src/main/resources/verity/context/`. `ContextLoader` gains a `loadBundled()` method that reads these classpath resources. `RunCommand` replaces its hardcoded `MAESTRO_CONTEXT` with `loadBundled()`. The MCP `get_context` tool falls back to bundled defaults when no path is configured, so external LLM agents get the same guidance without requiring `--context-path`. The `skills/context/` markdown files remain as human-readable copies for AI skill consumers (who read them directly as markdown, not via classpath).

---

### Task 1: Add bundled context resources to `:verity:core`

**Files:**
- Create: `verity/core/src/main/resources/verity/context/maestro.md`
- Create: `verity/core/src/main/resources/verity/context/tv-controls.md`

**Step 1: Create resources directory**

```bash
mkdir -p verity/core/src/main/resources/verity/context
```

**Step 2: Copy content from skills files**

Copy the content of `verity/skills/context/maestro.md` → `verity/core/src/main/resources/verity/context/maestro.md`.

Copy the content of `verity/skills/context/tv-controls.md` → `verity/core/src/main/resources/verity/context/tv-controls.md`.

These are the two files that contain universal Maestro/platform knowledge needed by `NavigatorAgent`. The other skills context files (`procedures.md` = agent workflow guidance, `app.md` = user template) are not bundled — they serve different purposes.

**Step 3: Commit**

```bash
git add verity/core/src/main/resources/verity/context/
git commit -m "feat(core): add bundled Maestro and TV controls context as resources"
```

---

### Task 2: Add `ContextLoader.loadBundled()` with test

**Files:**
- Modify: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/context/ContextLoader.kt`
- Create: `verity/core/src/test/kotlin/me/chrisbanes/verity/core/context/ContextLoaderTest.kt`

**Step 1: Write the failing test**

```kotlin
package me.chrisbanes.verity.core.context

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isNotEmpty
import kotlin.test.Test

class ContextLoaderTest {
  @Test
  fun `loadBundled returns Maestro reference content`() {
    val bundled = ContextLoader.loadBundled()
    assertThat(bundled).isNotEmpty()
    assertThat(bundled).contains("Maestro")
    assertThat(bundled).contains("pressKey")
  }

  @Test
  fun `loadBundled returns TV controls content`() {
    val bundled = ContextLoader.loadBundled()
    assertThat(bundled).contains("Remote Dpad")
    assertThat(bundled).contains("D-pad")
  }

  @Test
  fun `loadBundled concatenates all resource files`() {
    val bundled = ContextLoader.loadBundled()
    // Both files should be present
    assertThat(bundled).contains("Maestro YAML Reference")
    assertThat(bundled).contains("TV Remote Controls")
  }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :verity:core:test --tests "me.chrisbanes.verity.core.context.ContextLoaderTest"
```

Expected: FAIL — `loadBundled()` does not exist yet.

**Step 3: Implement `loadBundled()`**

Add to `ContextLoader.kt`:

```kotlin
fun loadBundled(): String {
  val resourceDir = "verity/context"
  val files = listOf("maestro.md", "tv-controls.md")
  return files.mapNotNull { filename ->
    ContextLoader::class.java.classLoader
      ?.getResourceAsStream("$resourceDir/$filename")
      ?.bufferedReader()
      ?.use { it.readText().trim() }
  }.joinToString("\n\n")
}
```

Note: The file list is explicit rather than using classpath scanning. Classpath resource directories cannot be reliably listed across all JVM environments, and an explicit list is clearer about what's bundled.

**Step 4: Run test to verify it passes**

```bash
./gradlew :verity:core:test --tests "me.chrisbanes.verity.core.context.ContextLoaderTest"
```

Expected: PASS

**Step 5: Commit**

```bash
git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/context/ContextLoader.kt \
       verity/core/src/test/kotlin/me/chrisbanes/verity/core/context/ContextLoaderTest.kt
git commit -m "feat(core): add ContextLoader.loadBundled() for classpath resources"
```

---

### Task 3: Replace `RunCommand.MAESTRO_CONTEXT` with `loadBundled()`

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`

**Step 1: Update RunCommand**

Replace line 68:
```kotlin
bundledContext = MAESTRO_CONTEXT,
```

with:
```kotlin
bundledContext = ContextLoader.loadBundled(),
```

Remove the entire `companion object` block (lines 117–131) — the `MAESTRO_CONTEXT` constant is no longer needed.

**Step 2: Run existing CLI tests**

```bash
./gradlew :verity:cli:test
```

Expected: PASS (existing tests don't depend on `MAESTRO_CONTEXT` content)

**Step 3: Commit**

```bash
git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt
git commit -m "refactor(cli): use ContextLoader.loadBundled() instead of hardcoded MAESTRO_CONTEXT"
```

---

### Task 4: Update MCP `get_context` to fall back to bundled defaults

**Files:**
- Modify: `verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt`
- Modify: `verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt`

**Step 1: Write a failing test**

Add a test that calls `get_context` with no path configured and no path argument, and expects bundled defaults to be returned.

```kotlin
@Test
fun `get_context returns bundled defaults when no path configured`() = runTest {
  // Server created with no contextPath (default)
  val server = VerityMcpServer(sessionManager = sessionManager, snapshotStore = snapshotStore)
  // ... (use the existing test pattern to call the get_context tool with no args)
  // Assert result contains "Maestro" and "Remote Dpad"
}
```

Follow the existing test patterns in `VerityMcpServerTest.kt` for how to invoke tools.

**Step 2: Run test to verify it fails**

```bash
./gradlew :verity:mcp:test --tests "me.chrisbanes.verity.mcp.VerityMcpServerTest"
```

Expected: FAIL — currently returns an error when no path is configured.

**Step 3: Update `registerGetContext`**

In `VerityMcpServer.kt`, change the `get_context` handler (lines 569–608). Replace the error case:

```kotlin
else -> return@addTool error(
  "No context path configured. Use the 'path' parameter or start the server with --context-path.",
)
```

with a fallback to bundled defaults:

```kotlin
else -> {
  // No path configured — return bundled defaults
  val bundled = ContextLoader.loadBundled()
  return@addTool if (bundled.isNotBlank()) {
    success(bundled)
  } else {
    error("No context path configured and no bundled defaults found.")
  }
}
```

Also update the main path: when a `contextDir` is resolved (either from arg or `--context-path`), prepend bundled defaults so app-specific context augments rather than replaces:

```kotlin
val appContext = withContext(Dispatchers.IO) {
  ContextLoader.load(contextDir)
}
val bundled = ContextLoader.loadBundled()
val combined = listOf(bundled, appContext).filter { it.isNotBlank() }.joinToString("\n\n")
if (combined.isBlank()) {
  success("No context files found in: ${contextDir.absolutePath}")
} else {
  success(combined)
}
```

**Step 4: Run tests**

```bash
./gradlew :verity:mcp:test
```

Expected: PASS

**Step 5: Commit**

```bash
git add verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt \
       verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt
git commit -m "feat(mcp): get_context falls back to bundled defaults"
```

---

### Task 5: Full verification

**Step 1: Run full check**

```bash
./gradlew check
```

Expected: ALL PASS

**Step 2: Run spotless**

```bash
./gradlew spotlessApply
```

---

## Verification

Bundled context refactor is complete when:
- `ContextLoader.loadBundled()` returns rich Maestro + TV controls content from classpath resources
- `RunCommand` uses `loadBundled()` instead of a hardcoded string
- MCP `get_context` returns bundled defaults when no path is configured
- MCP `get_context` prepends bundled defaults to app-specific context when a path is provided
- `./gradlew check` passes
