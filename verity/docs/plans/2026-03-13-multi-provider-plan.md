# Multi-Provider LLM Support — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the hardcoded Anthropic provider in the CLI with a provider registry that supports all 9 Koog-backed LLM providers, configurable via `verity/config.yaml` and CLI flags.

**Architecture:** A `VerityProvider` sealed class maps each Koog provider to its client constructor, default models, and env var. A `VerityConfig` data class parsed from YAML provides project-level defaults. Resolution priority: CLI flags > config file > provider defaults. Only `:verity:cli` changes.

**Tech Stack:** Koog 0.6.4 provider clients, Kaml (YAML parsing), Clikt (CLI flags)

**Design doc:** `docs/plans/2026-03-13-multi-provider-design.md`

---

### Task 1: Add provider dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `verity/cli/build.gradle.kts`

**Step 1: Add new library entries to version catalog**

Add these lines to the `[libraries]` section of `gradle/libs.versions.toml`, after the existing `koog-anthropic` line:

```toml
koog-openai = { module = "ai.koog:prompt-executor-openai-client", version.ref = "koog" }
koog-google = { module = "ai.koog:prompt-executor-google-client", version.ref = "koog" }
koog-openrouter = { module = "ai.koog:prompt-executor-openrouter-client", version.ref = "koog" }
koog-bedrock = { module = "ai.koog:prompt-executor-bedrock-client", version.ref = "koog" }
koog-deepseek = { module = "ai.koog:prompt-executor-deepseek-client", version.ref = "koog" }
koog-mistral = { module = "ai.koog:prompt-executor-mistralai-client", version.ref = "koog" }
koog-ollama = { module = "ai.koog:prompt-executor-ollama-client", version.ref = "koog" }
koog-dashscope = { module = "ai.koog:prompt-executor-dashscope-client", version.ref = "koog" }
```

**Step 2: Add dependencies to CLI build.gradle.kts**

Add all 8 new provider deps plus `kaml` and `kotlinx-serialization-json` (for config parsing) to `verity/cli/build.gradle.kts`:

```kotlin
dependencies {
  implementation(project(":verity:core"))
  implementation(project(":verity:device"))
  implementation(project(":verity:agent"))
  implementation(project(":verity:mcp"))
  implementation(libs.clikt)
  implementation(libs.koog.agents)
  implementation(libs.koog.anthropic)
  implementation(libs.koog.openai)
  implementation(libs.koog.google)
  implementation(libs.koog.openrouter)
  implementation(libs.koog.bedrock)
  implementation(libs.koog.deepseek)
  implementation(libs.koog.mistral)
  implementation(libs.koog.ollama)
  implementation(libs.koog.dashscope)
  implementation(libs.kaml)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
}
```

**Step 3: Verify it compiles**

Run: `./gradlew :verity:cli:compileKotlin`
Expected: BUILD SUCCESSFUL (no code changes, just deps resolving)

**Step 4: Commit**

```bash
git add gradle/libs.versions.toml verity/cli/build.gradle.kts
git commit -m "build(cli): add all 9 Koog LLM provider dependencies"
```

---

### Task 2: Create VerityProvider sealed class

**Files:**
- Create: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityProvider.kt`
- Create: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityProviderTest.kt`

**Step 1: Write the provider tests**

```kotlin
package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlin.test.Test
import kotlin.test.assertFailsWith

class VerityProviderTest {
  @Test
  fun `resolves anthropic by name`() {
    val provider = VerityProvider.fromName("anthropic")
    assertThat(provider).isInstanceOf<VerityProvider.Anthropic>()
    assertThat(provider.envVar).isEqualTo("ANTHROPIC_API_KEY")
  }

  @Test
  fun `resolves openai by name`() {
    val provider = VerityProvider.fromName("openai")
    assertThat(provider).isInstanceOf<VerityProvider.OpenAI>()
    assertThat(provider.envVar).isEqualTo("OPENAI_API_KEY")
  }

  @Test
  fun `resolves ollama by name`() {
    val provider = VerityProvider.fromName("ollama")
    assertThat(provider).isInstanceOf<VerityProvider.Ollama>()
    assertThat(provider.requiresAuth).isEqualTo(false)
  }

  @Test
  fun `all 9 providers registered`() {
    assertThat(VerityProvider.all.size).isEqualTo(9)
  }

  @Test
  fun `unknown provider throws`() {
    assertFailsWith<IllegalStateException> {
      VerityProvider.fromName("nonexistent")
    }
  }

  @Test
  fun `findModel returns matching model`() {
    val provider = VerityProvider.fromName("anthropic")
    val model = provider.findModel("claude-haiku-4-5")
    assertThat(model).isNotNull()
    assertThat(model.id).isEqualTo("claude-haiku-4-5")
  }

  @Test
  fun `findModel throws for unknown model`() {
    val provider = VerityProvider.fromName("anthropic")
    assertFailsWith<IllegalStateException> {
      provider.findModel("nonexistent-model")
    }
  }

  @Test
  fun `each provider has valid default models`() {
    VerityProvider.all.forEach { provider ->
      assertThat(provider.defaultNavigatorModel.id).isNotNull()
      assertThat(provider.defaultInspectorModel.id).isNotNull()
    }
  }
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :verity:cli:test`
Expected: FAIL — `VerityProvider` class doesn't exist yet

**Step 3: Write the VerityProvider sealed class**

Create `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityProvider.kt`.

This file contains the sealed class with all 9 provider entries. Each entry specifies:
- `name`: CLI/config identifier
- `defaultNavigatorModel`: cheap/fast model for YAML generation
- `defaultInspectorModel`: capable model for assertion evaluation
- `envVar`: environment variable for the API key
- `requiresAuth`: whether an API key is mandatory (false only for Ollama)
- `createClient(apiKey)`: creates the Koog `LLMClient` for this provider
- `allModels()`: returns all models known to this provider (from the Koog `*Models` object)

**Important:** Verify exact Koog model object names by checking the `*Models.kt` source files in `~/.gradle/caches/modules-2/files-2.1/ai.koog/prompt-executor-*-client-jvm/0.6.4/` — extract the sources JAR and read the Models file for each provider. The design doc's model names are best-guesses and may need adjustment.

Special cases:
- **Bedrock**: `createClient` reads `AWS_SECRET_ACCESS_KEY` from env and uses `StaticCredentialsProvider`.
- **Ollama**: `createClient` treats `apiKey` as the host URL, defaulting to `http://localhost:11434` if blank.
- **DeepSeek**: No vision support — note this but don't block; the inspector's tree fallback handles it.

The companion object provides:
- `val all: List<VerityProvider>` — all 9 entries
- `fun fromName(name: String): VerityProvider` — lookup by name, throws for unknown

Each provider also has `fun findModel(id: String): LLModel` that searches `allModels()` by `LLModel.id`.

**Step 4: Run tests to verify they pass**

Run: `./gradlew :verity:cli:test`
Expected: PASS

**Step 5: Commit**

```bash
git add verity/cli/src/
git commit -m "feat(cli): add VerityProvider sealed class with all 9 Koog providers"
```

---

### Task 3: Create VerityConfig and config loader

**Files:**
- Create: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityConfig.kt`
- Create: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityConfigTest.kt`

**Step 1: Write the config tests**

```kotlin
package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class VerityConfigTest {
  @Test
  fun `parses full config`() {
    val yaml = """
      provider: openai
      navigator-model: gpt-4o-mini
      inspector-model: gpt-4o
    """.trimIndent()
    val config = VerityConfig.fromYaml(yaml)
    assertThat(config.provider).isEqualTo("openai")
    assertThat(config.navigatorModel).isEqualTo("gpt-4o-mini")
    assertThat(config.inspectorModel).isEqualTo("gpt-4o")
  }

  @Test
  fun `parses partial config with only provider`() {
    val yaml = "provider: google"
    val config = VerityConfig.fromYaml(yaml)
    assertThat(config.provider).isEqualTo("google")
    assertThat(config.navigatorModel).isNull()
    assertThat(config.inspectorModel).isNull()
  }

  @Test
  fun `empty yaml returns empty config`() {
    val config = VerityConfig.fromYaml("{}")
    assertThat(config.provider).isNull()
    assertThat(config.navigatorModel).isNull()
    assertThat(config.inspectorModel).isNull()
  }

  @Test
  fun `loads from missing file returns empty config`() {
    val config = VerityConfig.loadOrDefault(java.io.File("/nonexistent/path/config.yaml"))
    assertThat(config).isEqualTo(VerityConfig())
  }
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :verity:cli:test`
Expected: FAIL — `VerityConfig` class doesn't exist yet

**Step 3: Write the VerityConfig data class**

```kotlin
package me.chrisbanes.verity.cli

import com.charleskorn.kaml.Yaml
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VerityConfig(
  val provider: String? = null,
  @SerialName("navigator-model") val navigatorModel: String? = null,
  @SerialName("inspector-model") val inspectorModel: String? = null,
) {
  companion object {
    fun fromYaml(yaml: String): VerityConfig =
      Yaml.default.decodeFromString(serializer(), yaml)

    fun loadOrDefault(file: File): VerityConfig =
      if (file.exists()) fromYaml(file.readText()) else VerityConfig()
  }
}
```

**Note:** The `:verity:cli` module needs the `kotlin-serialization` plugin. Add `alias(libs.plugins.kotlin.serialization)` to `verity/cli/build.gradle.kts` in the `plugins` block.

**Step 4: Run tests to verify they pass**

Run: `./gradlew :verity:cli:test`
Expected: PASS

**Step 5: Commit**

```bash
git add verity/cli/
git commit -m "feat(cli): add VerityConfig YAML parsing with Kaml"
```

---

### Task 4: Add CLI flags and resolution logic

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/Verity.kt`
- Create: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/ConfigResolverTest.kt`

**Step 1: Write resolution tests**

These test the priority chain: CLI flags > config file > provider defaults.

```kotlin
package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test

class ConfigResolverTest {
  @Test
  fun `defaults to anthropic when no config or flags`() {
    val resolved = resolveProvider(
      cliProvider = null,
      config = VerityConfig(),
    )
    assertThat(resolved).isInstanceOf<VerityProvider.Anthropic>()
  }

  @Test
  fun `config overrides default provider`() {
    val resolved = resolveProvider(
      cliProvider = null,
      config = VerityConfig(provider = "openai"),
    )
    assertThat(resolved).isInstanceOf<VerityProvider.OpenAI>()
  }

  @Test
  fun `cli flag overrides config provider`() {
    val resolved = resolveProvider(
      cliProvider = "google",
      config = VerityConfig(provider = "openai"),
    )
    assertThat(resolved).isInstanceOf<VerityProvider.Google>()
  }

  @Test
  fun `resolveModel uses cli flag first`() {
    val provider = VerityProvider.Anthropic
    val model = resolveModel(
      cliModel = "claude-sonnet-4-5",
      configModel = "claude-haiku-4-5",
      default = provider.defaultNavigatorModel,
      provider = provider,
    )
    assertThat(model.id).isEqualTo("claude-sonnet-4-5")
  }

  @Test
  fun `resolveModel uses config when no cli flag`() {
    val provider = VerityProvider.Anthropic
    val model = resolveModel(
      cliModel = null,
      configModel = "claude-sonnet-4-5",
      default = provider.defaultNavigatorModel,
      provider = provider,
    )
    assertThat(model.id).isEqualTo("claude-sonnet-4-5")
  }

  @Test
  fun `resolveModel uses provider default when no cli or config`() {
    val provider = VerityProvider.Anthropic
    val model = resolveModel(
      cliModel = null,
      configModel = null,
      default = provider.defaultNavigatorModel,
      provider = provider,
    )
    assertThat(model.id).isEqualTo(provider.defaultNavigatorModel.id)
  }
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :verity:cli:test`
Expected: FAIL — `resolveProvider` and `resolveModel` don't exist

**Step 3: Add resolution functions**

Add these top-level functions to a new file or to `VerityConfig.kt` (whichever feels cleaner — a reasonable choice is to put them at the bottom of `VerityConfig.kt` since they operate on config data):

```kotlin
fun resolveProvider(cliProvider: String?, config: VerityConfig): VerityProvider {
  val name = cliProvider ?: config.provider ?: "anthropic"
  return VerityProvider.fromName(name)
}

fun resolveModel(
  cliModel: String?,
  configModel: String?,
  default: LLModel,
  provider: VerityProvider,
): LLModel {
  val id = cliModel ?: configModel ?: return default
  return provider.findModel(id)
}
```

**Step 4: Update Verity.kt with new CLI flags**

Modify `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/Verity.kt`:

- Add `--provider` option (String?, no default — resolution logic handles default)
- Add `--navigator-model` option (String?)
- Add `--inspector-model` option (String?)
- Remove `envvar = "ANTHROPIC_API_KEY"` from `--api-key` (env var is now dynamic per provider)

```kotlin
val provider: String? by option("--provider", help = "LLM provider (e.g., anthropic, openai, google, ollama)")
val navigatorModel: String? by option("--navigator-model", help = "Override navigator model ID")
val inspectorModel: String? by option("--inspector-model", help = "Override inspector model ID")
val apiKey: String? by option("--api-key", help = "LLM API key (or set provider-specific env var)")
```

**Step 5: Run tests to verify they pass**

Run: `./gradlew :verity:cli:test`
Expected: PASS

**Step 6: Commit**

```bash
git add verity/cli/src/
git commit -m "feat(cli): add --provider, --navigator-model, --inspector-model flags with resolution logic"
```

---

### Task 5: Refactor RunCommand to use provider registry

**Files:**
- Modify: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt`

**Step 1: Rewrite RunCommand**

Replace the hardcoded Anthropic wiring with the resolution logic. The new flow:

1. Load config from `verity/config.yaml` via `VerityConfig.loadOrDefault(File("verity/config.yaml"))`
2. Resolve provider via `resolveProvider(parent.provider, config)`
3. Resolve API key: `parent.apiKey ?: System.getenv(provider.envVar)` — error if null and `provider.requiresAuth`
4. Resolve navigator and inspector models via `resolveModel(...)`
5. Create executor: `SingleLLMPromptExecutor(provider.createClient(apiKey))`
6. Create `AIAgent` instances using the resolved models instead of hardcoded `AnthropicModels.*`

The key changes from the current code:
- Remove `import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient`
- Remove `import ai.koog.prompt.executor.clients.anthropic.AnthropicModels`
- Replace `SingleLLMPromptExecutor(AnthropicLLMClient(apiKey))` with `SingleLLMPromptExecutor(provider.createClient(apiKey))`
- Replace `AnthropicModels.Haiku_4_5` with the resolved `navigatorModel`
- Replace `AnthropicModels.Sonnet_4_5` (both occurrences) with the resolved `inspectorModel`
- Update the error message from "Set ANTHROPIC_API_KEY" to reference the provider's env var dynamically

**Step 2: Run tests**

Run: `./gradlew :verity:cli:test`
Expected: PASS

**Step 3: Commit**

```bash
git add verity/cli/src/
git commit -m "feat(cli): refactor RunCommand to use provider registry"
```

---

### Task 6: Update smoke tests and full verification

**Files:**
- Modify: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityTest.kt`

**Step 1: Update help test to verify new flags**

Add assertions for the new CLI flags in the existing help test:

```kotlin
assertThat(result.output).contains("--provider")
assertThat(result.output).contains("--navigator-model")
assertThat(result.output).contains("--inspector-model")
```

**Step 2: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL — all tests pass, spotless clean

**Step 3: Run CLI help to verify output**

Run: `./gradlew :verity:cli:run --args="--help"`
Expected: Help output shows all new flags alongside existing ones.

**Step 4: Run spotless**

Run: `./gradlew spotlessApply`
Then: `./gradlew check`

**Step 5: Commit**

```bash
git add verity/cli/src/
git commit -m "test(cli): update smoke tests for multi-provider flags"
```

---

## Verification

After all tasks, the CLI should:

1. Default to Anthropic when no config or flags are set (backwards compatible)
2. Accept `--provider openai` (or any of 9 providers) to switch providers
3. Accept `--navigator-model` and `--inspector-model` to override defaults
4. Read `verity/config.yaml` for project-level defaults
5. Show all new flags in `--help` output

Run `./gradlew :verity:cli:run --args="--help"` to verify the complete help output.
