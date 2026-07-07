# Project Config Defaults Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand `verity/config.yaml` so `run`, `list`, and `mcp` share project defaults while CLI arguments still win.

**Architecture:** Keep config loading and option precedence in `:verity:cli`, add assertion strategy as core parsing behavior, and pass resolved defaults into commands and the MCP server. Use small resolver types so command wiring stays thin and tests can cover precedence without starting devices or LLM clients.

**Tech Stack:** Kotlin/JVM 21, Clikt, Kaml, kotlinx.serialization, assertk, kotlinx-coroutines-test, MCP Kotlin SDK, Gradle via `rtk ./gradlew`.

---

## File Structure

- Modify `verity/core/src/main/kotlin/me/chrisbanes/verity/core/model/AssertionStrategy.kt`: new core enum that maps implicit assertions to `AssertMode`.
- Modify `verity/core/src/main/kotlin/me/chrisbanes/verity/core/parser/JourneyStepSerializer.kt`: thread assertion strategy through parsing.
- Modify `verity/core/src/main/kotlin/me/chrisbanes/verity/core/journey/JourneyLoader.kt`: accept assertion strategy for YAML and file loading.
- Add `verity/core/src/test/kotlin/me/chrisbanes/verity/core/parser/AssertionStrategyParserTest.kt`: parser behavior for pinned, generic, and natural-language assertions.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityConfig.kt`: structured config DTOs plus legacy LLM key compatibility.
- Add `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ResolvedProjectConfig.kt`: shared CLI-over-config resolver for command defaults.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/Verity.kt`: nullable root CLI options for config-over-default precedence.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`: use resolved config for journey selection, LLM, device, context, output, and assertion strategy.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ListCommand.kt`: use resolved journey directory.
- Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/McpCommand.kt`: pass resolved MCP defaults.
- Modify `verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt`: add default journey directory and default session options.
- Modify `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityConfigTest.kt`: structured and legacy parsing tests.
- Modify `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/ConfigResolverTest.kt`: precedence and validation tests.
- Add `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunJourneyResolverTest.kt`: journey fallback path behavior.
- Modify `verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt`: MCP default wiring tests.
- Modify `docs/architecture.md`: document config shape and precedence.

## Task 1: Core Assertion Strategy

**Files:**
- Create: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/model/AssertionStrategy.kt`
- Modify: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/parser/JourneyStepSerializer.kt`
- Modify: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/journey/JourneyLoader.kt`
- Test: `verity/core/src/test/kotlin/me/chrisbanes/verity/core/parser/AssertionStrategyParserTest.kt`

- [ ] **Step 1: Write failing parser tests**

Create `verity/core/src/test/kotlin/me/chrisbanes/verity/core/parser/AssertionStrategyParserTest.kt`:

```kotlin
package me.chrisbanes.verity.core.parser

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.journey.JourneyLoader

class AssertionStrategyParserTest {
  @Test
  fun `infer strategy preserves existing generic assertion inference`() {
    val step = JourneyStepParser.parse("[?] Home", AssertionStrategy.INFER)

    assertThat(step).isEqualTo(JourneyStep.Assert("Home", AssertMode.VISIBLE))
  }

  @Test
  fun `configured strategy applies to generic assertions`() {
    val step = JourneyStepParser.parse("[?] Home", AssertionStrategy.TREE)

    assertThat(step).isEqualTo(JourneyStep.Assert("Home", AssertMode.TREE))
  }

  @Test
  fun `configured strategy applies to natural language assertions`() {
    val step = JourneyStepParser.parse("Verify Home is visible", AssertionStrategy.VISUAL)

    assertThat(step).isEqualTo(JourneyStep.Assert("Home is visible", AssertMode.VISUAL))
  }

  @Test
  fun `pinned assertion mode overrides configured strategy`() {
    val step = JourneyStepParser.parse("[?focused] Home", AssertionStrategy.VISUAL)

    assertThat(step).isEqualTo(JourneyStep.Assert("Home", AssertMode.FOCUSED))
  }

  @Test
  fun `journey loader applies configured strategy before step strings are parsed`() {
    val journey = JourneyLoader.fromYaml(
      """
      name: Strategy
      app: com.example
      platform: android-tv
      steps:
        - [?] Home
        - [?focused] Settings
        - Verify Details screen
      """.trimIndent(),
      AssertionStrategy.VISUAL,
    )

    assertThat(journey.steps).isEqualTo(
      listOf(
        JourneyStep.Assert("Home", AssertMode.VISUAL),
        JourneyStep.Assert("Settings", AssertMode.FOCUSED),
        JourneyStep.Assert("Details screen", AssertMode.VISUAL),
      ),
    )
  }
}
```

- [ ] **Step 2: Run parser tests and verify failure**

Run:

```bash
rtk ./gradlew :verity:core:test --tests "me.chrisbanes.verity.core.parser.AssertionStrategyParserTest"
```

Expected: compile failure because `AssertionStrategy` and the new parser overload do not exist.

- [ ] **Step 3: Add core assertion strategy enum**

Create `verity/core/src/main/kotlin/me/chrisbanes/verity/core/model/AssertionStrategy.kt`:

```kotlin
package me.chrisbanes.verity.core.model

enum class AssertionStrategy {
  INFER,
  VISIBLE,
  FOCUSED,
  TREE,
  VISUAL,
  ;

  companion object {
    val validNames: List<String> = entries.map { it.configName }

    fun fromConfig(value: String): AssertionStrategy {
      val normalized = value.trim().lowercase()
      return entries.firstOrNull { it.configName == normalized }
        ?: throw IllegalArgumentException(
          "Invalid assertions.strategy '$value'. Expected one of: ${validNames.joinToString()}",
        )
    }
  }
}

val AssertionStrategy.configName: String
  get() = name.lowercase().replace('_', '-')
```

- [ ] **Step 4: Thread strategy through step parsing**

Modify `JourneyStepParser.parse` in `verity/core/src/main/kotlin/me/chrisbanes/verity/core/parser/JourneyStepSerializer.kt`:

```kotlin
private fun AssertionStrategy.resolveMode(description: String): AssertMode = when (this) {
  AssertionStrategy.INFER -> AssertModeInferrer.infer(description)
  AssertionStrategy.VISIBLE -> AssertMode.VISIBLE
  AssertionStrategy.FOCUSED -> AssertMode.FOCUSED
  AssertionStrategy.TREE -> AssertMode.TREE
  AssertionStrategy.VISUAL -> AssertMode.VISUAL
}

fun parse(
  text: String,
  assertionStrategy: AssertionStrategy = AssertionStrategy.INFER,
): JourneyStep {
  val trimmed = text.trim()

  PINNED_ASSERT_PATTERN.matchEntire(trimmed)?.let { match ->
    val modeName = match.groupValues[1].lowercase()
    val description = match.groupValues[2].trim()
    val mode = MODE_MAP[modeName]
      ?: error("Unknown assert mode: $modeName. Valid modes: ${MODE_MAP.keys}")
    return JourneyStep.Assert(description = description, mode = mode)
  }

  GENERIC_ASSERT_PATTERN.matchEntire(trimmed)?.let { match ->
    val description = match.groupValues[1].trim()
    return JourneyStep.Assert(
      description = description,
      mode = assertionStrategy.resolveMode(description),
    )
  }

  LoopStepInferrer.infer(trimmed)?.let { return it }

  AssertionStepInferrer.infer(trimmed)?.let { inferred ->
    return when (inferred) {
      is JourneyStep.Assert -> inferred.copy(
        mode = assertionStrategy.resolveMode(inferred.description),
      )

      else -> inferred
    }
  }

  return JourneyStep.Action(instruction = trimmed)
}
```

Add this import:

```kotlin
import me.chrisbanes.verity.core.model.AssertionStrategy
```

- [ ] **Step 5: Thread strategy through journey loading**

Modify `verity/core/src/main/kotlin/me/chrisbanes/verity/core/journey/JourneyLoader.kt`:

```kotlin
package me.chrisbanes.verity.core.journey

import com.charleskorn.kaml.Yaml
import java.io.File
import kotlinx.serialization.Serializable
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.Journey
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.parser.JourneyStepParser

object JourneyLoader {

  private val yaml = Yaml.default

  fun fromYaml(
    yamlText: String,
    assertionStrategy: AssertionStrategy = AssertionStrategy.INFER,
  ): Journey {
    if (assertionStrategy == AssertionStrategy.INFER) {
      return yaml.decodeFromString(Journey.serializer(), yamlText)
    }
    val raw = yaml.decodeFromString(RawJourney.serializer(), yamlText)
    return Journey(
      name = raw.name,
      app = raw.app,
      platform = raw.platform,
      steps = raw.steps.map { JourneyStepParser.parse(it, assertionStrategy) },
    )
  }

  fun fromFile(
    file: File,
    assertionStrategy: AssertionStrategy = AssertionStrategy.INFER,
  ): Journey = fromYaml(file.readText(), assertionStrategy)

  fun listJourneyFiles(directory: File): List<File> = directory.listFiles()
    ?.filter { it.isFile && it.name.endsWith(".journey.yaml") }
    ?.sortedBy { it.name }
    ?: emptyList()
}

@Serializable
private data class RawJourney(
  val name: String,
  val app: String,
  val platform: Platform,
  val steps: List<String>,
)
```

- [ ] **Step 6: Run parser tests and commit**

Run:

```bash
rtk ./gradlew :verity:core:test --tests "me.chrisbanes.verity.core.parser.AssertionStrategyParserTest"
```

Expected: PASS.

Commit:

```bash
rtk git add verity/core/src/main/kotlin/me/chrisbanes/verity/core/model/AssertionStrategy.kt verity/core/src/main/kotlin/me/chrisbanes/verity/core/parser/JourneyStepSerializer.kt verity/core/src/main/kotlin/me/chrisbanes/verity/core/journey/JourneyLoader.kt verity/core/src/test/kotlin/me/chrisbanes/verity/core/parser/AssertionStrategyParserTest.kt
rtk git commit -m "feat: add configurable assertion strategy"
```

## Task 2: Structured Config DTOs and Resolvers

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityConfig.kt`
- Create: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ResolvedProjectConfig.kt`
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityConfigTest.kt`
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/ConfigResolverTest.kt`

- [ ] **Step 1: Write failing config parsing tests**

Add these tests to `VerityConfigTest`:

```kotlin
@Test
fun `parses structured config`() {
  val yaml =
    """
    paths:
      journeys: journeys
      context: context
      output: build/verity
    device:
      platform: android-tv
      id: emulator-5554
      disable-animations: true
    llm:
      provider: anthropic
      navigator-model: claude-haiku-4-5
      inspector-model: claude-sonnet-4-5
    assertions:
      strategy: tree
    """.trimIndent()

  val config = VerityConfig.fromYaml(yaml)

  assertThat(config.paths?.journeys).isEqualTo("journeys")
  assertThat(config.paths?.context).isEqualTo("context")
  assertThat(config.paths?.output).isEqualTo("build/verity")
  assertThat(config.device?.platform).isEqualTo("android-tv")
  assertThat(config.device?.id).isEqualTo("emulator-5554")
  assertThat(config.device?.disableAnimations).isEqualTo(true)
  assertThat(config.llm?.provider).isEqualTo("anthropic")
  assertThat(config.llm?.navigatorModel).isEqualTo("claude-haiku-4-5")
  assertThat(config.llm?.inspectorModel).isEqualTo("claude-sonnet-4-5")
  assertThat(config.assertions?.strategy).isEqualTo("tree")
}

@Test
fun `structured llm values override legacy top-level values`() {
  val config = VerityConfig.fromYaml(
    """
    provider: openai
    navigator-model: gpt-4o-mini
    inspector-model: gpt-4o
    llm:
      provider: anthropic
      navigator-model: claude-haiku-4-5
      inspector-model: claude-sonnet-4-5
    """.trimIndent(),
  )

  assertThat(config.effectiveProvider).isEqualTo("anthropic")
  assertThat(config.effectiveNavigatorModel).isEqualTo("claude-haiku-4-5")
  assertThat(config.effectiveInspectorModel).isEqualTo("claude-sonnet-4-5")
}
```

- [ ] **Step 2: Write failing resolver tests**

Add these tests to `ConfigResolverTest`:

```kotlin
@Test
fun `resolved config uses cli values before config values`() {
  val config = VerityConfig(
    paths = VerityPathsConfig(
      journeys = "config-journeys",
      context = "config-context",
      output = "config-output",
    ),
    device = VerityDeviceConfig(
      platform = "android-tv",
      id = "config-device",
      disableAnimations = false,
    ),
    llm = VerityLlmConfig(
      provider = "openai",
      navigatorModel = "gpt-4o-mini",
      inspectorModel = "gpt-4o",
    ),
    assertions = VerityAssertionsConfig(strategy = "tree"),
  )

  val resolved = ResolvedProjectConfig.resolve(
    config = config,
    cli = ProjectCliOptions(
      journeysPath = "cli-journeys",
      contextPath = "cli-context",
      outputPath = "cli-output",
      platform = "ios",
      deviceId = "cli-device",
      disableAnimations = true,
      provider = "anthropic",
      navigatorModel = "claude-haiku-4-5",
      inspectorModel = "claude-sonnet-4-5",
      assertionStrategy = "visual",
    ),
  )

  assertThat(resolved.journeysPath.path).isEqualTo("cli-journeys")
  assertThat(resolved.contextPath?.path).isEqualTo("cli-context")
  assertThat(resolved.outputPath.path).isEqualTo("cli-output")
  assertThat(resolved.platform).isEqualTo(Platform.IOS)
  assertThat(resolved.deviceId).isEqualTo("cli-device")
  assertThat(resolved.disableAnimations).isEqualTo(true)
  assertThat(resolved.provider.name).isEqualTo("anthropic")
  assertThat(resolved.navigatorModel.id).isEqualTo("claude-haiku-4-5")
  assertThat(resolved.inspectorModel.id).isEqualTo("claude-sonnet-4-5")
  assertThat(resolved.assertionStrategy).isEqualTo(AssertionStrategy.VISUAL)
}

@Test
fun `resolved config preserves current defaults when config and cli are empty`() {
  val resolved = ResolvedProjectConfig.resolve(
    config = VerityConfig(),
    cli = ProjectCliOptions(),
  )

  assertThat(resolved.journeysPath.path).isEqualTo(".")
  assertThat(resolved.contextPath).isNull()
  assertThat(resolved.outputPath.path).isEqualTo("build/verity")
  assertThat(resolved.platform).isNull()
  assertThat(resolved.deviceId).isNull()
  assertThat(resolved.disableAnimations).isEqualTo(false)
  assertThat(resolved.provider.name).isEqualTo("anthropic")
  assertThat(resolved.assertionStrategy).isEqualTo(AssertionStrategy.INFER)
}
```

Add imports:

```kotlin
import assertk.assertions.isNull
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.Platform
```

- [ ] **Step 3: Run resolver tests and verify failure**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests "me.chrisbanes.verity.cli.VerityConfigTest" --tests "me.chrisbanes.verity.cli.ConfigResolverTest"
```

Expected: compile failure because structured DTOs and `ResolvedProjectConfig` do not exist.

- [ ] **Step 4: Replace config DTO with structured shape**

Replace `VerityConfig` in `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityConfig.kt` with:

```kotlin
@Serializable
data class VerityConfig(
  val paths: VerityPathsConfig? = null,
  val device: VerityDeviceConfig? = null,
  val llm: VerityLlmConfig? = null,
  val assertions: VerityAssertionsConfig? = null,
  val provider: String? = null,
  @SerialName("navigator-model") val navigatorModel: String? = null,
  @SerialName("inspector-model") val inspectorModel: String? = null,
) {
  val effectiveProvider: String?
    get() = llm?.provider ?: provider

  val effectiveNavigatorModel: String?
    get() = llm?.navigatorModel ?: navigatorModel

  val effectiveInspectorModel: String?
    get() = llm?.inspectorModel ?: inspectorModel

  companion object {
    fun fromYaml(yaml: String): VerityConfig = Yaml.default.decodeFromString(serializer(), yaml)

    fun loadOrDefault(file: File): VerityConfig {
      if (!file.exists()) return VerityConfig()
      val text = file.readText().trim()
      return if (text.isEmpty()) VerityConfig() else fromYaml(text)
    }
  }
}

@Serializable
data class VerityPathsConfig(
  val journeys: String? = null,
  val context: String? = null,
  val output: String? = null,
)

@Serializable
data class VerityDeviceConfig(
  val platform: String? = null,
  val id: String? = null,
  @SerialName("disable-animations") val disableAnimations: Boolean? = null,
)

@Serializable
data class VerityLlmConfig(
  val provider: String? = null,
  @SerialName("navigator-model") val navigatorModel: String? = null,
  @SerialName("inspector-model") val inspectorModel: String? = null,
)

@Serializable
data class VerityAssertionsConfig(
  val strategy: String? = null,
)
```

Update `resolveProvider` and existing model callers to use `config.effectiveProvider`, `config.effectiveNavigatorModel`, and `config.effectiveInspectorModel`.

- [ ] **Step 5: Add shared resolved config**

Create `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ResolvedProjectConfig.kt`:

```kotlin
package me.chrisbanes.verity.cli

import java.io.File
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.Platform

data class ProjectCliOptions(
  val journeysPath: String? = null,
  val contextPath: String? = null,
  val outputPath: String? = null,
  val platform: String? = null,
  val deviceId: String? = null,
  val disableAnimations: Boolean? = null,
  val provider: String? = null,
  val navigatorModel: String? = null,
  val inspectorModel: String? = null,
  val assertionStrategy: String? = null,
)

data class ResolvedProjectConfig(
  val journeysPath: File,
  val contextPath: File?,
  val outputPath: File,
  val platform: Platform?,
  val deviceId: String?,
  val disableAnimations: Boolean,
  val provider: VerityProvider,
  val navigatorModel: ai.koog.prompt.llm.LLModel,
  val inspectorModel: ai.koog.prompt.llm.LLModel,
  val assertionStrategy: AssertionStrategy,
) {
  companion object {
    fun resolve(
      config: VerityConfig,
      cli: ProjectCliOptions,
    ): ResolvedProjectConfig {
      val provider = resolveProvider(cli.provider, config)
      return ResolvedProjectConfig(
        journeysPath = File(cli.journeysPath ?: config.paths?.journeys ?: "."),
        contextPath = (cli.contextPath ?: config.paths?.context)?.let(::File),
        outputPath = File(cli.outputPath ?: config.paths?.output ?: "build/verity"),
        platform = resolvePlatform(cli.platform ?: config.device?.platform),
        deviceId = cli.deviceId ?: config.device?.id,
        disableAnimations = cli.disableAnimations ?: config.device?.disableAnimations ?: false,
        provider = provider,
        navigatorModel = resolveModel(
          cliModel = cli.navigatorModel,
          configModel = config.effectiveNavigatorModel,
          default = provider.defaultNavigatorModel,
          provider = provider,
        ),
        inspectorModel = resolveModel(
          cliModel = cli.inspectorModel,
          configModel = config.effectiveInspectorModel,
          default = provider.defaultInspectorModel,
          provider = provider,
        ),
        assertionStrategy = resolveAssertionStrategy(
          cli.assertionStrategy ?: config.assertions?.strategy,
        ),
      )
    }
  }
}

fun resolvePlatform(value: String?): Platform? = when (value) {
  null -> null
  "android-tv" -> Platform.ANDROID_TV
  "android" -> Platform.ANDROID_MOBILE
  "ios" -> Platform.IOS
  else -> throw IllegalArgumentException(
    "Invalid device.platform '$value'. Expected one of: android-tv, android, ios",
  )
}

fun resolveAssertionStrategy(value: String?): AssertionStrategy =
  value?.let(AssertionStrategy::fromConfig) ?: AssertionStrategy.INFER
```

- [ ] **Step 6: Run resolver tests and commit**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests "me.chrisbanes.verity.cli.VerityConfigTest" --tests "me.chrisbanes.verity.cli.ConfigResolverTest"
```

Expected: PASS.

Commit:

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityConfig.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ResolvedProjectConfig.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityConfigTest.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/ConfigResolverTest.kt
rtk git commit -m "feat: add project config resolvers"
```

## Task 3: Run and List Command Wiring

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/Verity.kt`
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ListCommand.kt`
- Test: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunJourneyResolverTest.kt`

- [ ] **Step 1: Add pure journey resolver tests**

Create `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunJourneyResolverTest.kt`:

```kotlin
package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class RunJourneyResolverTest {
  @TempDir
  lateinit var tempDir: File

  @Test
  fun `cli journey argument wins`() {
    val cliFile = tempDir.resolve("cli.journey.yaml").apply { writeText("name: CLI") }
    val configFile = tempDir.resolve("config.journey.yaml").apply { writeText("name: Config") }

    val resolved = resolveRunJourneyFile(
      cliJourneyPath = cliFile.path,
      configuredJourneysPath = configFile,
    )

    assertThat(resolved).isEqualTo(cliFile)
  }

  @Test
  fun `single configured journey file is used when cli argument is omitted`() {
    val configFile = tempDir.resolve("config.journey.yaml").apply { writeText("name: Config") }

    val resolved = resolveRunJourneyFile(
      cliJourneyPath = null,
      configuredJourneysPath = configFile,
    )

    assertThat(resolved).isEqualTo(configFile)
  }

  @Test
  fun `single journey in configured directory is used when cli argument is omitted`() {
    val journeysDir = tempDir.resolve("journeys").apply { mkdirs() }
    val journeyFile = journeysDir.resolve("only.journey.yaml").apply { writeText("name: Only") }

    val resolved = resolveRunJourneyFile(
      cliJourneyPath = null,
      configuredJourneysPath = journeysDir,
    )

    assertThat(resolved).isEqualTo(journeyFile)
  }

  @Test
  fun `multiple journeys in configured directory fail clearly`() {
    val journeysDir = tempDir.resolve("journeys").apply { mkdirs() }
    journeysDir.resolve("one.journey.yaml").writeText("name: One")
    journeysDir.resolve("two.journey.yaml").writeText("name: Two")

    val error = assertFailsWith<IllegalArgumentException> {
      resolveRunJourneyFile(
        cliJourneyPath = null,
        configuredJourneysPath = journeysDir,
      )
    }

    assertThat(error.message).contains("Multiple journey files found")
  }
}
```

- [ ] **Step 2: Run new resolver tests and verify failure**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests "me.chrisbanes.verity.cli.RunJourneyResolverTest"
```

Expected: compile failure because `resolveRunJourneyFile` does not exist.

- [ ] **Step 3: Add nullable CLI options**

Modify `Verity.kt` root options:

```kotlin
val journeysPath: String? by option("--journeys-path", help = "Default journey file or directory")
val outputPath: String? by option("--output-path", help = "Directory for generated run artifacts")
val assertionStrategy: String? by option(
  "--assertion-strategy",
  help = "Implicit assertion strategy: infer, visible, focused, tree, or visual",
)
val noAnimations: Boolean? by option("--no-animations", help = "Disable device animations")
  .flag(default = null)
```

Keep existing root options for device, platform, provider, models, API key, context path, and bundled context.

- [ ] **Step 4: Add run journey resolver**

Add this helper to `RunCommand.kt` or a new `RunJourneyResolver.kt` in the same package:

```kotlin
fun resolveRunJourneyFile(
  cliJourneyPath: String?,
  configuredJourneysPath: File,
): File {
  cliJourneyPath?.let { return File(it) }

  if (configuredJourneysPath.isFile) return configuredJourneysPath

  require(configuredJourneysPath.isDirectory) {
    "Journey path is not a file or directory: $configuredJourneysPath"
  }

  val journeyFiles = JourneyLoader.listJourneyFiles(configuredJourneysPath)
  return when (journeyFiles.size) {
    1 -> journeyFiles.single()
    0 -> throw IllegalArgumentException(
      "No journey files found in ${configuredJourneysPath.path}. Provide a journey path.",
    )

    else -> throw IllegalArgumentException(
      "Multiple journey files found in ${configuredJourneysPath.path}. Provide a journey path.",
    )
  }
}
```

- [ ] **Step 5: Wire resolved config into `run`**

In `RunCommand.run`, replace direct config/model/session path resolution with:

```kotlin
val parent = currentContext.parent?.command as Verity
val config = VerityConfig.loadOrDefault(File("verity/config.yaml"))
val resolved = ResolvedProjectConfig.resolve(
  config = config,
  cli = parent.projectCliOptions(),
)
val provider = resolved.provider

val apiKey = parent.apiKey ?: System.getenv(provider.envVar)
if (provider.requiresAuth && apiKey == null) {
  throw UsageError("API key required. Set ${provider.envVar} or use --api-key")
}

val file = try {
  resolveRunJourneyFile(journeyPath, resolved.journeysPath)
} catch (e: IllegalArgumentException) {
  throw UsageError(e.message ?: "Invalid journey path")
}
if (!file.exists()) throw CliktError("Journey file not found: $file")

val journey = JourneyLoader.fromFile(file, resolved.assertionStrategy)
val platform = resolved.platform ?: journey.platform
val session = DeviceSessionFactory.connect(
  platform = platform,
  deviceId = resolved.deviceId,
  disableAnimations = resolved.disableAnimations,
)
val executor = SingleLLMPromptExecutor(provider.createClient(apiKey ?: ""))
```

Use `resolved.navigatorModel`, `resolved.inspectorModel`, and `resolved.contextPath` in the existing agent setup. Add this helper to `Verity.kt`:

```kotlin
fun Verity.projectCliOptions(): ProjectCliOptions = ProjectCliOptions(
  journeysPath = journeysPath,
  contextPath = contextPath,
  outputPath = outputPath,
  platform = platform?.serialName,
  deviceId = device,
  disableAnimations = noAnimations,
  provider = provider,
  navigatorModel = navigatorModel,
  inspectorModel = inspectorModel,
  assertionStrategy = assertionStrategy,
)
```

Add `serialName` near resolver code:

```kotlin
val Platform.serialName: String
  get() = when (this) {
    Platform.ANDROID_TV -> "android-tv"
    Platform.ANDROID_MOBILE -> "android"
    Platform.IOS -> "ios"
  }
```

- [ ] **Step 6: Wire resolved config into `list`**

In `ListCommand`, change `path` to nullable:

```kotlin
private val path by option("--path", help = "Directory to search for journeys")
```

Then resolve:

```kotlin
val parent = currentContext.parent?.command as Verity
val config = VerityConfig.loadOrDefault(File("verity/config.yaml"))
val resolved = ResolvedProjectConfig.resolve(
  config = config,
  cli = parent.projectCliOptions().copy(journeysPath = path ?: parent.journeysPath),
)
val dir = resolved.journeysPath
require(dir.isDirectory) { "Not a directory: ${dir.path}" }
```

Use `dir.path` in output messages.

- [ ] **Step 7: Run CLI tests and commit**

Run:

```bash
rtk ./gradlew :verity:cli:test
```

Expected: PASS.

Commit:

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/Verity.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ListCommand.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/RunJourneyResolverTest.kt
rtk git commit -m "feat: apply project config to cli commands"
```

## Task 4: MCP Defaults

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/McpCommand.kt`
- Modify: `verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt`
- Test: `verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt`

- [ ] **Step 1: Write MCP default tests**

Add these tests to `verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt`:

```kotlin
@Test
fun `list journeys uses configured default directory when path argument omitted`() = runTest {
  val journeysDir = createTempDir(prefix = "verity-journeys")
  journeysDir.resolve("sample.journey.yaml").writeText(
    """
    name: Sample
    app: com.example
    platform: android-tv
    steps:
      - [?] Home
    """.trimIndent(),
  )

  val server = VerityMcpServer(defaultJourneysPath = journeysDir).create()
  val result = server.tools["list_journeys"]!!.handler.invoke(
    StubClientConnection(),
    CallToolRequest(CallToolRequestParams(name = "list_journeys")),
  )

  val text = (result.content.first() as TextContent).text
  assertThat(result.isError).isIn(null, false)
  assertThat(text).contains("sample.journey.yaml")
}

@Test
fun `open session uses configured defaults when tool args are omitted`() = runTest {
  var capturedPlatform: Platform? = null
  var capturedDeviceId: String? = null
  var capturedDisableAnimations: Boolean? = null
  val fakeSession = me.chrisbanes.verity.device.FakeDeviceSession()
  val sessionManager = McpDeviceSessionManager(
    sessionFactory = { platform, deviceId, disableAnimations ->
      capturedPlatform = platform
      capturedDeviceId = deviceId
      capturedDisableAnimations = disableAnimations
      fakeSession
    },
  )
  val server = VerityMcpServer(
    sessionManager = sessionManager,
    defaultPlatform = Platform.ANDROID_TV,
    defaultDeviceId = "configured-device",
    defaultDisableAnimations = true,
  ).create()

  val result = server.tools["open_session"]!!.handler.invoke(
    StubClientConnection(),
    CallToolRequest(CallToolRequestParams(name = "open_session")),
  )

  assertThat(result.isError).isIn(null, false)
  assertThat(capturedPlatform).isEqualTo(Platform.ANDROID_TV)
  assertThat(capturedDeviceId).isEqualTo("configured-device")
  assertThat(capturedDisableAnimations).isEqualTo(true)
}
```

Add imports if missing:

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import me.chrisbanes.verity.core.model.Platform
```

- [ ] **Step 2: Run MCP tests and verify failure**

Run:

```bash
rtk ./gradlew :verity:mcp:test --tests "me.chrisbanes.verity.mcp.VerityMcpServerTest"
```

Expected: compile failure because server default constructor parameters do not exist.

- [ ] **Step 3: Add MCP server defaults**

Extend `VerityMcpServer` constructor:

```kotlin
class VerityMcpServer(
  private val sessionManager: McpDeviceSessionManager = McpDeviceSessionManager(),
  private val snapshotStore: McpHierarchySnapshotStore = McpHierarchySnapshotStore(),
  private val contextPath: File? = null,
  private val skipBundledContext: Boolean = false,
  private val defaultJourneysPath: File = File("."),
  private val defaultPlatform: Platform? = null,
  private val defaultDeviceId: String? = null,
  private val defaultDisableAnimations: Boolean = false,
)
```

In `registerListJourneys`, replace:

```kotlin
val dir = File(args.string("path") ?: ".")
```

with:

```kotlin
val dir = args.string("path")?.let(::File) ?: defaultJourneysPath
```

In `registerOpenSession`, replace platform/device/animation resolution with:

```kotlin
val platform = args.string("platform")?.let(::parsePlatform)
  ?: defaultPlatform
  ?: throw IllegalArgumentException(
    "Missing required parameter: platform. Provide platform or configure device.platform.",
  )
val device = args.string("device") ?: defaultDeviceId
val disableAnimations = args.bool("disable_animations") ?: defaultDisableAnimations
```

Remove `required = listOf("platform")` from `open_session` if present. Keep validation inside the handler so the error can mention the config fallback.

- [ ] **Step 4: Wire MCP command resolved defaults**

In `McpCommand.run`, load and resolve config:

```kotlin
val parent = currentContext.parent?.command as Verity
val config = VerityConfig.loadOrDefault(File("verity/config.yaml"))
val resolved = ResolvedProjectConfig.resolve(
  config = config,
  cli = parent.projectCliOptions(),
)

val server = VerityMcpServer(
  contextPath = resolved.contextPath,
  skipBundledContext = parent.noBundledContext,
  defaultJourneysPath = resolved.journeysPath,
  defaultPlatform = resolved.platform,
  defaultDeviceId = resolved.deviceId,
  defaultDisableAnimations = resolved.disableAnimations,
)
```

- [ ] **Step 5: Run MCP tests and commit**

Run:

```bash
rtk ./gradlew :verity:mcp:test
```

Expected: PASS.

Commit:

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/McpCommand.kt verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt
rtk git commit -m "feat: apply project config to mcp defaults"
```

## Task 5: Validation and Architecture Docs

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ResolvedProjectConfig.kt`
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/ConfigResolverTest.kt`
- Modify: `docs/architecture.md`

- [ ] **Step 1: Add validation tests**

Add tests to `ConfigResolverTest`:

```kotlin
@Test
fun `invalid platform message names accepted values`() {
  val error = assertFailsWith<IllegalArgumentException> {
    resolvePlatform("watch")
  }

  assertThat(error.message).contains("device.platform")
  assertThat(error.message).contains("android-tv")
  assertThat(error.message).contains("android")
  assertThat(error.message).contains("ios")
}

@Test
fun `invalid assertion strategy message names accepted values`() {
  val error = assertFailsWith<IllegalArgumentException> {
    resolveAssertionStrategy("expensive")
  }

  assertThat(error.message).contains("assertions.strategy")
  assertThat(error.message).contains("infer")
  assertThat(error.message).contains("visible")
  assertThat(error.message).contains("focused")
  assertThat(error.message).contains("tree")
  assertThat(error.message).contains("visual")
}
```

Add imports:

```kotlin
import assertk.assertions.contains
import kotlin.test.assertFailsWith
```

- [ ] **Step 2: Add path validation helper**

Add to `ResolvedProjectConfig.kt`:

```kotlin
fun validateReadableDirectory(path: File, fieldName: String) {
  require(path.isDirectory) {
    "$fieldName must be a directory: ${path.path}"
  }
}

fun validateOutputDirectory(path: File) {
  require(!path.exists() || path.isDirectory) {
    "paths.output must be a directory when it already exists: ${path.path}"
  }
}
```

Use `validateOutputDirectory(resolved.outputPath)` in `run` after resolving config. Use `validateReadableDirectory` in `list` and MCP list handling where the path is required to be a directory.

- [ ] **Step 3: Update architecture docs**

Add a `Project Configuration` section to `docs/architecture.md` near the CLI architecture content:

````markdown
## Project Configuration

Verity reads optional project defaults from `verity/config.yaml`. Missing and empty config files preserve current command defaults.

```yaml
paths:
  journeys: journeys
  context: context
  output: build/verity

device:
  platform: android-tv
  id: emulator-5554
  disable-animations: true

llm:
  provider: anthropic
  navigator-model: claude-haiku-4-5
  inspector-model: claude-sonnet-4-5

assertions:
  strategy: infer
```

Precedence is always CLI flag or argument, then config value, then built-in default. Legacy top-level `provider`, `navigator-model`, and `inspector-model` keys remain supported; structured `llm` values win when both forms are present.

`run` uses config for journey fallback, context, output, device, LLM, animation, and assertion strategy defaults. `list` uses `paths.journeys` unless `--path` is provided. `mcp` uses config for default context, journeys, platform, device, and animation handling, while MCP tool arguments still override server defaults.

`assertions.strategy` controls implicit assertion modes only. Explicit prefixes such as `[?visual]` remain authoritative. `infer` preserves current heuristic behavior, while `visible`, `focused`, `tree`, and `visual` force that mode for `[?]` and natural-language inferred assertions.
````

- [ ] **Step 4: Run docs and validation checks**

Run:

```bash
rtk ./gradlew :verity:cli:test --tests "me.chrisbanes.verity.cli.ConfigResolverTest"
rtk ./gradlew spotlessCheck
```

Expected: PASS.

Commit:

```bash
rtk git add verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ResolvedProjectConfig.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ListCommand.kt verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/ConfigResolverTest.kt docs/architecture.md
rtk git commit -m "docs: document project config defaults"
```

## Task 6: Full Verification

**Files:**
- Review all files changed by Tasks 1-5.

- [ ] **Step 1: Run full check**

Run:

```bash
rtk ./gradlew check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Review final diff**

Run:

```bash
rtk git status --short --branch
rtk git log --oneline -5
```

Expected: working tree clean after the final commit, with recent commits matching the task commits.

- [ ] **Step 3: Prepare final summary**

Report:

- Config shape added.
- Commands covered: `run`, `list`, `mcp`.
- Assertion strategy behavior.
- Validation and architecture docs.
- Full `rtk ./gradlew check` result.
