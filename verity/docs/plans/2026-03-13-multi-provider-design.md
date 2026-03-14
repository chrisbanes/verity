# Multi-Provider LLM Support — Design

## Goal

Replace the hardcoded Anthropic provider in the CLI with a provider registry that supports all 9 Koog-backed LLM providers. Users select their provider and models via a config file (`verity/config.yaml`) or CLI flags.

## Provider Registry

A `VerityProvider` sealed class in `:verity:cli` maps each Koog provider to its defaults:

```kotlin
sealed class VerityProvider(
  val name: String,
  val defaultNavigatorModel: LLModel,
  val defaultInspectorModel: LLModel,
  val envVar: String,
) {
  abstract fun createClient(apiKey: String): LLMClient
}
```

### Supported Providers

| Provider | Name | Navigator (cheap) | Inspector (capable) | Env Var | Vision |
|----------|------|-------------------|---------------------|---------|--------|
| Anthropic | `anthropic` | `Haiku_4_5` | `Opus_4_5` | `ANTHROPIC_API_KEY` | Yes |
| OpenAI | `openai` | `GPT4oMini` | `GPT4o` | `OPENAI_API_KEY` | Yes |
| Google | `google` | `Gemini2_0FlashLite` | `Gemini2_5Pro` | `GOOGLE_API_KEY` | Yes |
| OpenRouter | `openrouter` | `Phi4Reasoning` | `Claude4_5Opus` | `OPENROUTER_API_KEY` | Yes |
| Bedrock | `bedrock` | `Claude3Haiku` | `Claude3Opus` | `AWS_ACCESS_KEY_ID` | Yes |
| DeepSeek | `deepseek` | `DeepSeekChat` | `DeepSeekReasoner` | `DEEPSEEK_API_KEY` | No |
| Mistral AI | `mistralai` | `MistralSmall2` | `MagistralMedium12` | `MISTRAL_API_KEY` | Yes |
| Ollama | `ollama` | `LLAMA_3_2_3B` | `LLAMA_4_SCOUT` | None | Limited |
| DashScope | `dashscope` | `QWEN_FLASH` | `QWEN3_OMNI_FLASH` | `DASHSCOPE_API_KEY` | Yes |

### Special Cases

- **Bedrock**: Uses AWS credentials (`AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY`) instead of a single API key.
- **Ollama**: No API key required. Reads `OLLAMA_HOST` env var (or `--api-key` as host URL), defaulting to `http://localhost:11434`.
- **DeepSeek**: No vision support. Visual assertions fall back to tree-only evaluation.

## Config File

Location: `verity/config.yaml` in project root. Parsed with Kaml.

```yaml
provider: anthropic
navigator-model: claude-haiku-4-5
inspector-model: claude-sonnet-4-5
```

All fields optional. Missing file defaults to Anthropic with provider defaults.

```kotlin
@Serializable
data class VerityConfig(
  val provider: String? = null,
  @SerialName("navigator-model") val navigatorModel: String? = null,
  @SerialName("inspector-model") val inspectorModel: String? = null,
)
```

## CLI Flags

New shared options on the root `Verity` command:

```
--provider <name>       LLM provider (default: from config or anthropic)
--navigator-model <id>  Override navigator model ID
--inspector-model <id>  Override inspector model ID
```

Existing `--api-key` flag remains. Its `envvar` becomes dynamic based on the resolved provider.

## Resolution Priority

**CLI flags > config file > provider defaults**

1. Load config from `verity/config.yaml` (if exists).
2. Resolve provider: CLI `--provider` > config `provider` > `anthropic`.
3. Resolve API key: CLI `--api-key` > provider's env var.
4. Resolve models: CLI `--navigator-model` > config `navigator-model` > provider default.
5. Create executor via `provider.createClient(apiKey)` wrapped in `SingleLLMPromptExecutor`.
6. Look up `LLModel` objects by string ID from the provider's model list.

## RunCommand Changes

RunCommand becomes thin — resolves the provider, models, and API key, then delegates to Orchestrator the same as today but with resolved values instead of hardcoded ones.

## Dependencies

New entries in `libs.versions.toml` for all 8 additional Koog provider artifacts (`koog-anthropic` already exists). All added to `:verity:cli` only — agent/core/device/mcp modules stay untouched.

## Testing

- Provider registry: each provider resolves correct client type and default models.
- `findModel(id)`: returns correct `LLModel` for a string ID.
- Config loading: missing file, partial config, full config.
- Resolution priority: CLI flags override config, config overrides defaults.
- No integration tests calling real LLM APIs.

## Scope

- Only the `:verity:cli` module changes.
- The agent module's provider-agnostic architecture (caller-owned Koog factories) is the foundation that makes this work.
