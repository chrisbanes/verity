# Project Context Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add structured project-context validation, required-context configuration, and loaded-file reporting for CLI runs and MCP `get_context`.

**Architecture:** Keep context discovery in `:verity:core` by extending `ContextLoader` with a structured `ContextBundle` result and a typed validation exception. Wire the shared result into CLI and MCP call sites so both surfaces use the same validation rules while preserving optional-context behavior.

**Tech Stack:** Kotlin/JVM 21, Clikt, kotlinx.coroutines, Kaml serialization, MCP Kotlin SDK, assertk, Gradle via `./gradlew`.

---

## File Structure

- Modify `verity/core/src/main/kotlin/me/chrisbanes/verity/core/context/ContextLoader.kt`
  - Owns bundled context loading and project markdown context loading.
  - Adds `ContextBundle`, `ContextStatus`, and `ContextValidationException`.
- Modify `verity/core/src/test/kotlin/me/chrisbanes/verity/core/context/ContextLoaderTest.kt`
  - Covers optional and required context validation.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityConfig.kt`
  - Adds `require-context` config parsing and `resolveRequiredContext`.
- Modify `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityConfigTest.kt`
  - Covers config parsing and precedence helper.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/Verity.kt`
  - Adds shared `--require-context`.
- Modify `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityTest.kt`
  - Covers help output for the new flag.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`
  - Validates context before device connection and prints context status.
- Modify `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandSmokeTest.kt`
  - Adds a command-level failure test for required missing context.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/McpCommand.kt`
  - Resolves config and passes required context into the MCP server.
- Modify `verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt`
  - Adds required-context validation and loaded-file reporting in `get_context`.
- Modify `verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt`
  - Covers optional missing context, loaded files, and required missing context.
- Modify `docs/architecture.md`
  - Documents `--require-context`, `require-context`, context statuses, and `get_context` metadata.

---

### Task 1: Core Context Validation Contract

**Files:**
- Modify: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/context/ContextLoader.kt`
- Test: `verity/core/src/test/kotlin/me/chrisbanes/verity/core/context/ContextLoaderTest.kt`

- [ ] **Step 1: Write failing tests for structured optional validation**

Add these imports to `ContextLoaderTest.kt`:

```kotlin
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
```

Add these tests to `ContextLoaderTest`:

```kotlin
@Test
fun `loadProject returns not configured for optional null directory`() {
  val bundle = ContextLoader.loadProject(directory = null, required = false)

  assertThat(bundle.status).isEqualTo(ContextStatus.NOT_CONFIGURED)
  assertThat(bundle.text).isEmpty()
  assertThat(bundle.loadedFiles).isEmpty()
}

@Test
fun `loadProject returns missing directory for optional nonexistent path`() {
  val directory = File("/nonexistent/path")

  val bundle = ContextLoader.loadProject(directory = directory, required = false)

  assertThat(bundle.status).isEqualTo(ContextStatus.MISSING_DIRECTORY)
  assertThat(bundle.text).isEmpty()
  assertThat(bundle.loadedFiles).isEmpty()
}

@Test
fun `loadProject returns empty directory for optional directory without markdown files`() {
  val dir = kotlin.io.path.createTempDirectory("empty-context").toFile()
  try {
    File(dir, "notes.txt").writeText("ignored")

    val bundle = ContextLoader.loadProject(directory = dir, required = false)

    assertThat(bundle.status).isEqualTo(ContextStatus.EMPTY_DIRECTORY)
    assertThat(bundle.text).isEmpty()
    assertThat(bundle.loadedFiles).isEmpty()
  } finally {
    dir.deleteRecursively()
  }
}
```

- [ ] **Step 2: Write failing tests for loaded files and required failures**

Add these tests to `ContextLoaderTest`:

```kotlin
@Test
fun `loadProject reports loaded markdown files in deterministic order`() {
  val dir = createTempContextDir(
    "b.markdown" to "Section B",
    "a.md" to "Section A",
    "notes.txt" to "ignored",
  )
  try {
    val bundle = ContextLoader.loadProject(directory = dir, required = true)

    assertThat(bundle.status).isEqualTo(ContextStatus.LOADED)
    assertThat(bundle.text).isEqualTo("Section A\n\nSection B")
    assertThat(bundle.loadedFiles.map { it.name }).containsExactly("a.md", "b.markdown")
  } finally {
    dir.deleteRecursively()
  }
}

@Test
fun `loadProject throws clear error when required context is not configured`() {
  assertThat {
    ContextLoader.loadProject(directory = null, required = true)
  }.isFailure().hasMessage("Required project context is not configured. Set --context-path before using --require-context.")
}

@Test
fun `loadProject throws clear error when required context directory is missing`() {
  val directory = File("/nonexistent/path")

  assertThat {
    ContextLoader.loadProject(directory = directory, required = true)
  }.isFailure().hasMessage(
    "Required project context directory does not exist or is not a directory: ${directory.absolutePath}",
  )
}

@Test
fun `loadProject throws clear error when required context directory is empty`() {
  val dir = kotlin.io.path.createTempDirectory("required-empty-context").toFile()
  try {
    assertThat {
      ContextLoader.loadProject(directory = dir, required = true)
    }.isFailure().hasMessage(
      "Required project context directory contains no markdown files: ${dir.absolutePath}",
    )
  } finally {
    dir.deleteRecursively()
  }
}
```

- [ ] **Step 3: Run the core context tests and verify they fail**

Run:

```bash
rtk ./gradlew :verity:core:test --tests me.chrisbanes.verity.core.context.ContextLoaderTest
```

Expected: FAIL because `loadProject`, `ContextStatus`, and `ContextValidationException` do not exist yet.

- [ ] **Step 4: Implement the structured context loader**

Replace `ContextLoader.kt` with:

```kotlin
package me.chrisbanes.verity.core.context

import java.io.File

data class ContextBundle(
  val text: String,
  val loadedFiles: List<File>,
  val status: ContextStatus,
)

enum class ContextStatus {
  LOADED,
  NOT_CONFIGURED,
  MISSING_DIRECTORY,
  EMPTY_DIRECTORY,
}

class ContextValidationException(message: String) : IllegalArgumentException(message)

object ContextLoader {

  private val markdownExtensions = setOf("md", "markdown")
  private val bundledCache: String by lazy { loadBundledFromClasspath() }

  fun loadBundled(): String = bundledCache

  private fun loadBundledFromClasspath(): String {
    val resourceDir = "verity/context"
    val files = listOf("maestro.md", "tv-controls.md")
    return files.mapNotNull { filename ->
      ContextLoader::class.java.classLoader
        ?.getResourceAsStream("$resourceDir/$filename")
        ?.bufferedReader()
        ?.use { it.readText().trim() }
    }.joinToString("\n\n")
  }

  fun load(directory: File): String = loadProject(directory = directory, required = false).text

  fun loadProject(directory: File?, required: Boolean): ContextBundle {
    val bundle = when {
      directory == null -> ContextBundle(
        text = "",
        loadedFiles = emptyList(),
        status = ContextStatus.NOT_CONFIGURED,
      )

      !directory.isDirectory -> ContextBundle(
        text = "",
        loadedFiles = emptyList(),
        status = ContextStatus.MISSING_DIRECTORY,
      )

      else -> {
        val files = directory.listFiles()
          ?.filter { it.isFile && it.extension in markdownExtensions }
          ?.sortedBy { it.name }
          ?: emptyList()

        if (files.isEmpty()) {
          ContextBundle(
            text = "",
            loadedFiles = emptyList(),
            status = ContextStatus.EMPTY_DIRECTORY,
          )
        } else {
          ContextBundle(
            text = files.joinToString("\n\n") { it.readText().trim() },
            loadedFiles = files,
            status = ContextStatus.LOADED,
          )
        }
      }
    }

    if (required && bundle.status != ContextStatus.LOADED) {
      throw ContextValidationException(bundle.requiredFailureMessage(directory))
    }

    return bundle
  }

  private fun ContextBundle.requiredFailureMessage(directory: File?): String = when (status) {
    ContextStatus.LOADED -> error("Loaded context is not a validation failure")
    ContextStatus.NOT_CONFIGURED ->
      "Required project context is not configured. Set --context-path before using --require-context."

    ContextStatus.MISSING_DIRECTORY ->
      "Required project context directory does not exist or is not a directory: ${directory!!.absolutePath}"

    ContextStatus.EMPTY_DIRECTORY ->
      "Required project context directory contains no markdown files: ${directory!!.absolutePath}"
  }
}
```

- [ ] **Step 5: Run the core context tests and verify they pass**

Run:

```bash
rtk ./gradlew :verity:core:test --tests me.chrisbanes.verity.core.context.ContextLoaderTest
```

Expected: PASS.

- [ ] **Step 6: Commit core context validation**

Run:

```bash
rtk git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/context/ContextLoader.kt verity/core/src/test/kotlin/me/chrisbanes/verity/core/context/ContextLoaderTest.kt
rtk git commit -m "feat: add project context validation"
```

---

### Task 2: CLI Required-Context Configuration

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityConfig.kt`
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/Verity.kt`
- Test: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityConfigTest.kt`
- Test: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityTest.kt`

- [ ] **Step 1: Write failing config tests**

Add these tests to `VerityConfigTest`:

```kotlin
@Test
fun `parses require context config`() {
  val yaml =
    """
    require-context: true
    """.trimIndent()

  val config = VerityConfig.fromYaml(yaml)

  assertThat(config.requireContext).isEqualTo(true)
}

@Test
fun `resolveRequiredContext uses cli flag before config`() {
  assertThat(resolveRequiredContext(cliRequireContext = true, config = VerityConfig(requireContext = false)))
    .isEqualTo(true)
}

@Test
fun `resolveRequiredContext uses config when cli flag is false`() {
  assertThat(resolveRequiredContext(cliRequireContext = false, config = VerityConfig(requireContext = true)))
    .isEqualTo(true)
}

@Test
fun `resolveRequiredContext defaults to false`() {
  assertThat(resolveRequiredContext(cliRequireContext = false, config = VerityConfig()))
    .isEqualTo(false)
}
```

- [ ] **Step 2: Write failing help test**

Add this assertion to `prints help message` in `VerityTest`:

```kotlin
assertThat(result.output).contains("--require-context")
```

- [ ] **Step 3: Run CLI tests and verify they fail**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.VerityConfigTest --tests me.chrisbanes.verity.cli.VerityTest
```

Expected: FAIL because `requireContext`, `resolveRequiredContext`, and `--require-context` do not exist yet.

- [ ] **Step 4: Implement config parsing and resolution**

Change `VerityConfig` to:

```kotlin
@Serializable
data class VerityConfig(
  val provider: String? = null,
  @SerialName("navigator-model") val navigatorModel: String? = null,
  @SerialName("inspector-model") val inspectorModel: String? = null,
  @SerialName("require-context") val requireContext: Boolean? = null,
)
```

Add this helper to `VerityConfig.kt`:

```kotlin
fun resolveRequiredContext(cliRequireContext: Boolean, config: VerityConfig): Boolean =
  cliRequireContext || config.requireContext == true
```

- [ ] **Step 5: Add the shared CLI flag**

Add this property to `Verity` after `contextPath`:

```kotlin
val requireContext: Boolean by option(
  "--require-context",
  help = "Fail when project context is not configured or contains no markdown files",
).flag()
```

- [ ] **Step 6: Run CLI config and help tests**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.VerityConfigTest --tests me.chrisbanes.verity.cli.VerityTest
```

Expected: PASS.

- [ ] **Step 7: Commit CLI configuration**

Run:

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityConfig.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/Verity.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityConfigTest.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityTest.kt
rtk git commit -m "feat: add required context configuration"
```

---

### Task 3: CLI Run Validation and Reporting

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandSmokeTest.kt`

- [ ] **Step 1: Write failing command test for required missing context**

Add these imports to `RunCommandSmokeTest.kt`:

```kotlin
import assertk.assertions.contains
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
```

Add this test:

```kotlin
@Test
fun `run command fails before device connection when required context is missing`() {
  val journeyUrl = javaClass.classLoader.getResource("smoke/minimal.journey.yaml")!!

  val result = Verity()
    .subcommands(RunCommand(), ListCommand(), McpCommand())
    .test(
      listOf(
        "--provider",
        "ollama",
        "--context-path",
        "/nonexistent/context",
        "--require-context",
        "run",
        java.io.File(journeyUrl.toURI()).absolutePath,
      ),
    )

  assertThat(result.statusCode).isEqualTo(1)
  assertThat(result.output)
    .contains("Required project context directory does not exist or is not a directory: /nonexistent/context")
}
```

- [ ] **Step 2: Run the new command test and verify it fails**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.RunCommandSmokeTest
```

Expected: FAIL because `RunCommand` still calls `ContextLoader.load(File)` and does not validate required context before connecting.

- [ ] **Step 3: Add context validation helpers to `RunCommand.kt`**

Add these imports:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.chrisbanes.verity.core.context.ContextBundle
import me.chrisbanes.verity.core.context.ContextStatus
import me.chrisbanes.verity.core.context.ContextValidationException
```

Add these private functions at file scope after the class:

```kotlin
private fun ContextBundle.describeForCli(contextDir: File?, required: Boolean): List<String> {
  val mode = if (required) "required" else "optional"
  return when (status) {
    ContextStatus.LOADED -> listOf("Project context: loaded ${loadedFiles.size} file(s)") +
      loadedFiles.map { "  - ${it.displayPath()}" }

    ContextStatus.NOT_CONFIGURED -> listOf("Project context: $mode, not configured")

    ContextStatus.MISSING_DIRECTORY -> listOf(
      "Project context: $mode, missing directory: ${contextDir!!.absolutePath}",
    )

    ContextStatus.EMPTY_DIRECTORY -> listOf(
      "Project context: $mode, no markdown files found in: ${contextDir!!.absolutePath}",
    )
  }
}

private fun File.displayPath(): String {
  val current = File("").absoluteFile.toPath().normalize()
  val target = absoluteFile.toPath().normalize()
  return runCatching { current.relativize(target).toString() }
    .getOrDefault(absolutePath)
}
```

- [ ] **Step 4: Validate context before device and LLM setup**

In `RunCommand.run`, after the journey metadata `echo` lines and before `DeviceSessionFactory.connect`, add:

```kotlin
val contextDir = parent.contextPath?.let { File(it) }
val requireContext = resolveRequiredContext(parent.requireContext, config)
val projectContext = try {
  withContext(Dispatchers.IO) {
    ContextLoader.loadProject(directory = contextDir, required = requireContext)
  }
} catch (e: ContextValidationException) {
  throw CliktError(e.message ?: "Project context validation failed")
}
projectContext.describeForCli(contextDir, requireContext).forEach { echo(it) }
```

Then remove the later line inside `session.use`:

```kotlin
val injectedContext = parent.contextPath?.let { ContextLoader.load(File(it)) } ?: ""
```

Pass `projectContext.text` into `Orchestrator`:

```kotlin
context = projectContext.text,
```

- [ ] **Step 5: Run CLI smoke tests**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests me.chrisbanes.verity.cli.RunCommandSmokeTest
```

Expected: PASS.

- [ ] **Step 6: Commit CLI run validation**

Run:

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunCommandSmokeTest.kt
rtk git commit -m "feat: validate context before cli runs"
```

---

### Task 4: MCP Context Validation and Metadata

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/McpCommand.kt`
- Modify: `verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt`
- Test: `verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt`

- [ ] **Step 1: Write failing MCP tests**

Add these imports to `VerityMcpServerTest.kt`:

```kotlin
import assertk.assertions.isTrue
import java.io.File
```

Add these tests:

```kotlin
@Test
fun `get_context reports optional missing configured path`() = runTest {
  val server = VerityMcpServer(contextPath = File("/nonexistent/context")).create()
  val tool = server.tools["get_context"]!!

  val result = tool.handler.invoke(
    StubClientConnection(),
    CallToolRequest(CallToolRequestParams(name = "get_context")),
  )

  val text = (result.content.first() as TextContent).text
  assertThat(result.isError).isIn(null, false)
  assertThat(text).contains("Project context: optional, missing directory: /nonexistent/context")
  assertThat(text).contains("Maestro")
}

@Test
fun `get_context reports loaded project context files`() = runTest {
  val dir = kotlin.io.path.createTempDirectory("mcp-context").toFile()
  try {
    File(dir, "app.md").writeText("# App context")
    val server = VerityMcpServer(contextPath = dir).create()
    val tool = server.tools["get_context"]!!

    val result = tool.handler.invoke(
      StubClientConnection(),
      CallToolRequest(CallToolRequestParams(name = "get_context")),
    )

    val text = (result.content.first() as TextContent).text
    assertThat(result.isError).isIn(null, false)
    assertThat(text).contains("Project context: loaded 1 file(s)")
    assertThat(text).contains("app.md")
    assertThat(text).contains("# App context")
  } finally {
    dir.deleteRecursively()
  }
}

@Test
fun `get_context errors when required configured path is missing`() = runTest {
  val server = VerityMcpServer(
    contextPath = File("/nonexistent/context"),
    requireContext = true,
  ).create()
  val tool = server.tools["get_context"]!!

  val result = tool.handler.invoke(
    StubClientConnection(),
    CallToolRequest(CallToolRequestParams(name = "get_context")),
  )

  val text = (result.content.first() as TextContent).text
  assertThat(result.isError).isTrue()
  assertThat(text)
    .contains("Required project context directory does not exist or is not a directory: /nonexistent/context")
}
```

- [ ] **Step 2: Run MCP tests and verify they fail**

Run:

```bash
rtk ./gradlew :verity:mcp:test --tests me.chrisbanes.verity.mcp.VerityMcpServerTest
```

Expected: FAIL because `VerityMcpServer` does not accept `requireContext` and `get_context` does not report metadata.

- [ ] **Step 3: Pass required context from `mcp` command**

In `McpCommand.run`, add config loading and required-context resolution:

```kotlin
val config = VerityConfig.loadOrDefault(File("verity/config.yaml"))
val requireContext = resolveRequiredContext(parent.requireContext, config)
```

Pass it into the server:

```kotlin
val server = VerityMcpServer(
  contextPath = contextDir,
  skipBundledContext = parent.noBundledContext,
  requireContext = requireContext,
)
```

- [ ] **Step 4: Add MCP server constructor parameter and helpers**

Add this constructor parameter to `VerityMcpServer`:

```kotlin
private val requireContext: Boolean = false,
```

Add imports:

```kotlin
import me.chrisbanes.verity.core.context.ContextBundle
import me.chrisbanes.verity.core.context.ContextStatus
import me.chrisbanes.verity.core.context.ContextValidationException
```

Add these helpers inside `VerityMcpServer`:

```kotlin
private fun ContextBundle.describeForMcp(contextDir: File?, required: Boolean): String {
  val mode = if (required) "required" else "optional"
  return when (status) {
    ContextStatus.LOADED -> buildString {
      appendLine("Project context: loaded ${loadedFiles.size} file(s)")
      loadedFiles.forEach { appendLine("- ${it.absolutePath}") }
    }.trim()

    ContextStatus.NOT_CONFIGURED -> "Project context: $mode, not configured"

    ContextStatus.MISSING_DIRECTORY ->
      "Project context: $mode, missing directory: ${contextDir!!.absolutePath}"

    ContextStatus.EMPTY_DIRECTORY ->
      "Project context: $mode, no markdown files found in: ${contextDir!!.absolutePath}"
  }
}
```

- [ ] **Step 5: Update `get_context` to use `loadProject`**

Replace the context loading block in `registerGetContext` with:

```kotlin
val pathArg = args.string("path")
val contextDir = pathArg?.let { File(it) } ?: contextPath
val projectContext = try {
  withContext(Dispatchers.IO) {
    ContextLoader.loadProject(directory = contextDir, required = requireContext)
  }
} catch (e: ContextValidationException) {
  return@addSafeTool error(e.message ?: "Project context validation failed")
}
val bundled = if (skipBundledContext) "" else ContextLoader.loadBundled()
val metadata = projectContext.describeForMcp(contextDir, requireContext)
val content = listOf(metadata, bundled, projectContext.text)
  .filter { it.isNotBlank() }
  .joinToString("\n\n")

if (content.isBlank()) {
  error("No context path configured and no bundled defaults found.")
} else {
  success(content)
}
```

This intentionally changes optional no-path behavior with bundled context from only bundled text to metadata plus bundled text.

- [ ] **Step 6: Run MCP tests**

Run:

```bash
rtk ./gradlew :verity:mcp:test --tests me.chrisbanes.verity.mcp.VerityMcpServerTest
```

Expected: PASS.

- [ ] **Step 7: Commit MCP validation**

Run:

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/McpCommand.kt verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt
rtk git commit -m "feat: validate mcp project context"
```

---

### Task 5: Architecture Documentation

**Files:**
- Modify: `docs/architecture.md`

- [ ] **Step 1: Update CLI shared options**

In `docs/architecture.md`, add this line to the shared options block after `--context-path <dir>`:

```text
--require-context      Fail if project context is missing or contains no markdown files
```

- [ ] **Step 2: Add config documentation**

Near the CLI section, add:

````markdown
### Configuration

`verity/config.yaml` can provide defaults for provider and model selection:

```yaml
provider: anthropic
navigator-model: claude-haiku-4-5
inspector-model: claude-opus-4-5
require-context: true
```

`require-context` makes `--context-path` mandatory for workflows that depend on project context. A CLI `--require-context` flag also enables this behavior for one invocation.
````

- [ ] **Step 3: Document context validation in execution data flow**

In the CLI Run flow, add context validation between journey loading and device connection:

```text
ContextLoader.loadProject()
    |-- LOADED -> report loaded markdown files, inject text into NavigatorAgent
    |-- NOT_CONFIGURED -> optional explicit status or required error
    |-- MISSING_DIRECTORY -> optional explicit status or required error
    `-- EMPTY_DIRECTORY -> optional explicit status or required error
```

- [ ] **Step 4: Update MCP `get_context` documentation**

Update the MCP tool table row for `get_context` to describe:

```markdown
| `get_context` | optional path | loaded-file metadata + bundled defaults + markdown context text | Required context can error |
```

- [ ] **Step 5: Commit docs**

Run:

```bash
rtk git add docs/architecture.md
rtk git commit -m "docs: document project context validation"
```

---

### Task 6: Formatting and Full Verification

**Files:**
- Verify all changed files.

- [ ] **Step 1: Apply formatting**

Run:

```bash
rtk ./gradlew spotlessApply
```

Expected: SUCCESS. If files change, inspect `rtk git diff --stat` and commit the formatting changes with the relevant task commit if still uncommitted, or a final formatting commit if earlier commits already exist.

- [ ] **Step 2: Run full check**

Run:

```bash
rtk ./gradlew check
```

Expected: SUCCESS.

- [ ] **Step 3: Check final git status**

Run:

```bash
rtk git status --short --branch
```

Expected: branch `github-issue-47-fix` with no unstaged or uncommitted changes.

- [ ] **Step 4: Final review**

Inspect the final diff:

```bash
rtk git log --oneline -6
rtk git diff HEAD~5..HEAD --stat
```

Expected: commits cover core validation, CLI config, CLI run validation, MCP validation, docs, and any formatting changes. No unrelated files are changed.
