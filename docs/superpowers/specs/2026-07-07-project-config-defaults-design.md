# Project Configuration Defaults Design

## Context

GitHub issue 46 asks Verity to expand `verity/config.yaml` so projects can avoid repeating long CLI arguments across `run`, `list`, and `mcp`.

The current config only supports top-level LLM defaults:

- `provider`
- `navigator-model`
- `inspector-model`

`run` reads those values directly from `verity/config.yaml`. `list` does not read config. `mcp` inherits root CLI flags for context behavior but does not use project config for journey or device defaults.

## Goals

- Add shared defaults for journey directory, context directory, output directory, platform, provider, models, device selection, animation handling, and assertion strategy.
- Apply config consistently across `verity run`, `verity list`, and `verity mcp` where each setting is relevant.
- Preserve current behavior when `verity/config.yaml` is missing or empty.
- Ensure CLI flags override config values.
- Fail invalid config values with clear validation messages.
- Cover parsing, precedence, and validation with tests.
- Update `docs/architecture.md` for the new config shape and command behavior.

## Non-Goals

- Do not add per-command config sections unless a setting genuinely needs command-specific behavior.
- Do not change explicit journey assertion syntax such as `[?visual]`.
- Do not add LLM behavior to the MCP module. MCP remains a raw device-control server.
- Do not move config loading into `:verity:core`; this remains CLI/application configuration.

## Config Shape

Use a structured YAML shape for new settings:

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

Keep backward compatibility with the existing top-level keys:

```yaml
provider: anthropic
navigator-model: claude-haiku-4-5
inspector-model: claude-sonnet-4-5
```

If both top-level and `llm` values are present, the structured `llm` value wins. This gives projects a clean migration path while making the new shape authoritative.

## Precedence

All resolved options use the same order:

1. CLI flag or argument
2. Config value
3. Existing default

This should live in shared resolver helpers rather than being reimplemented in each command.

## Command Behavior

### `verity run`

`run` should use config for:

- journey path when the positional journey argument is omitted
- context directory
- output directory for any run artifacts Verity persists
- platform override
- provider
- navigator model
- inspector model
- device ID
- disable animations
- assertion strategy

The positional journey argument remains valid and takes precedence over `paths.journeys`. If the argument is omitted and `paths.journeys` points to a single journey file, run that file. If it points to a directory with exactly one `*.journey.yaml`, run that journey. If it points to a directory with zero or multiple journeys, fail with a clear message asking the user to provide a journey path.

The output directory should be resolved and validated, but implementation should not invent new persistent artifacts solely to consume the setting. If a command or tool already saves an artifact, the output directory is its default base directory when no more specific path is provided.

### `verity list`

`list` should use `paths.journeys` as the default search directory. The existing `--path` flag remains and overrides config.

### `verity mcp`

`mcp` should use config for:

- default context directory used by `get_context`
- default journey directory used by `list_journeys`
- default platform, device ID, and animation behavior used by `open_session` when tool arguments are omitted

MCP tool arguments still override config. Required tool parameters can become optional only when a config default can safely supply the missing value. For example, `open_session.platform` can be optional if `device.platform` is configured, but must still fail clearly when neither a tool argument nor config provides a platform.

## Assertion Strategy

Add `assertions.strategy` with these values:

- `infer`: current behavior; infer from assertion text
- `visible`
- `focused`
- `tree`
- `visual`

The strategy applies only to unpinned assertions:

- `[?visual] Poster is shown` keeps `VISUAL`
- `[?] Poster is shown` uses the configured strategy unless it is `infer`
- `Verify Poster is shown` uses the configured strategy unless it is `infer`

This treats project config as the default for implicit assertion modes, while keeping explicit journey syntax authoritative.

## Validation

Config parsing should reject invalid values with messages that name the field and accepted values:

- unknown provider
- unknown model for the selected provider
- invalid platform
- invalid assertion strategy
- non-directory context path when used as a context directory
- invalid journeys path when used by `list` or as the fallback for `run`
- output path that exists as a non-directory file

Missing config files and empty config files still resolve to empty config.

## Testing

Add focused unit tests for:

- parsing the structured config shape
- parsing legacy top-level LLM keys
- structured `llm` values taking precedence over legacy top-level values
- CLI-over-config precedence for provider, models, platform, device, context, journeys path, output path, animation handling, and assertion strategy
- missing and empty config files preserving current defaults
- invalid provider, platform, model, and assertion strategy messages
- `list` using `paths.journeys` when `--path` is omitted
- MCP defaults flowing into `list_journeys`, `get_context`, and `open_session`
- explicit assertion modes overriding configured assertion strategy
- generic and natural-language inferred assertions using configured assertion strategy

Prefer pure resolver/parser tests where possible. Use command-level tests only for behavior that depends on Clikt parsing or MCP tool wiring.

## Architecture Documentation

Update `docs/architecture.md` to describe:

- the `verity/config.yaml` structured shape
- command precedence rules
- legacy top-level LLM key compatibility
- assertion strategy behavior
- which config values apply to `run`, `list`, and `mcp`
