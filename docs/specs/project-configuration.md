# Project configuration

Optional defaults in `verity/config.yaml` reduce repeated options across `run`, `list`, and `mcp`. This describes implemented behavior originating in the July 2026 design for [issue #46](https://github.com/chrisbanes/verity/issues/46).

## Shape and precedence

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

assertions:
  strategy: infer

require-context: true
```

`llm.navigator-model` and `llm.inspector-model` optionally select supported model IDs for the provider. Omitted models use provider defaults.

Resolved options use CLI input first, project configuration second, and built-in defaults last. Relative paths are interpreted from the working directory. Missing or blank configuration files supply no overrides.

Legacy top-level `provider`, `navigator-model`, and `inspector-model` remain supported. Each structured `llm` value overrides its corresponding legacy value; omitted structured values can still fall back to legacy values.

`--require-context` only enables required context. It is combined with `require-context: true`, so making context optional requires changing that configuration value. `--no-animations` enables animation disabling; supplying `--no-animations=false` overrides an enabled config default.

## Command behavior

| Command | Defaults it consumes |
| --- | --- |
| `run` | Journey fallback, context, output, device, provider/models, animations, assertion strategy, and required context |
| `list` | Journey directory, overridden by `list --path` or the shared `--journeys-path` option |
| `mcp` | Journey/context directories, required context, and session platform/device/animation defaults |

`run` still accepts a positional file or directory path. Omitting it uses the resolved configured journey path, including a directory with multiple journeys. `list` requires a directory and successfully reports an empty directory as having no journeys.

MCP tool arguments take precedence over server defaults. `open_session` requires a platform either in the tool arguments or the resolved server configuration; omission of both is an error. MCP does not use configured LLMs to execute tools. Its CLI startup, like `list`, currently uses the shared resolver and can reject invalid provider/model or assertion-strategy settings even when those settings are not consumed by the command.

## Assertion strategy

Accepted values are `infer`, `visible`, `focused`, `tree`, and `visual`. `infer` chooses a mode from the assertion text; the other values select that mode for assertions without an explicit mode.

Explicit prefixes stay authoritative: `[?visual] Poster is shown` remains visual even under `visible`. `[?] Poster is shown` and `Verify Poster is shown` follow the configured strategy. Choose an explicit mode when that particular expectation needs a different basis of evaluation from the project default.

## Validation

Unknown providers, unsupported models for the provider, invalid platforms, and invalid assertion strategies are rejected. Config platform values are `android-tv`, `android`, and `ios`. An output path that already exists as a file is invalid.

Path checks depend on the consumer: `list` requires a journey directory; `run` accepts a file or directory and applies the [suite rules](directory-suite-runs.md); CLI MCP startup requires an existing configured context directory. `run` uses the optional/required rules in the [project-context spec](project-context.md).

For normal `run`, config loading/resolution and output validation failures are setup failures with exit code `3`, occurring before a run directory exists. Input failures after that point follow the [artifact contract](run-artifacts.md). Fast-path-only [dry runs](dry-run.md) defer provider and model validation.

## Source and coverage

- [VerityConfig](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/VerityConfig.kt) and [ResolvedProjectConfig](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ResolvedProjectConfig.kt): schema, compatibility, and precedence.
- [Verity](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/Verity.kt), [ListCommand](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/ListCommand.kt), and [McpCommand](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/McpCommand.kt): options and command wiring.
- [VerityConfigTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/VerityConfigTest.kt), [ConfigResolverTest](../../verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/ConfigResolverTest.kt), and [AssertionStrategyParserTest](../../verity/core/src/test/kotlin/me/chrisbanes/verity/core/parser/AssertionStrategyParserTest.kt): parsing, precedence, and explicit modes.
