# Project Context Validation Design

## Issue

GitHub issue 47 asks Verity to validate project context before a run or MCP workflow depends on it. The current implementation loads optional markdown context through `ContextLoader`, but missing or malformed context directories collapse to an empty string. That makes configuration mistakes hard to distinguish from intentionally omitted optional context.

## Goals

- Validate configured project context directories before they are used.
- Report which project context files were loaded.
- Support required project context from both CLI flags and `verity/config.yaml`.
- Preserve optional context behavior when context is not required.
- Cover validation behavior with focused tests.

## Non-Goals

- Change bundled context loading semantics.
- Add non-markdown project context formats.
- Change the generated prompt structure beyond adding context metadata to user-visible output.
- Introduce a new config file location.

## Required Context Resolution

Required context is a resolved setting with this precedence:

1. CLI flag: `--require-context`
2. Config file: `require-context: true`
3. Default: `false`

The CLI flag only enables required context. There is no negative flag in this design. If `verity/config.yaml` sets `require-context: true`, a caller must remove or change that config value to make context optional.

## Core Contract

Add a structured project-context result in `:verity:core`, owned by the existing context package:

```kotlin
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
```

Add a new `ContextLoader.loadProject(directory: File?, required: Boolean): ContextBundle` API.

Behavior:

- `directory == null` returns `NOT_CONFIGURED` when optional.
- A non-existent path or non-directory path returns `MISSING_DIRECTORY` when optional.
- A valid directory with no `.md` or `.markdown` files returns `EMPTY_DIRECTORY` when optional.
- A valid directory with markdown files returns `LOADED`, sorted by file name, with concatenated trimmed content and the loaded file list.
- When `required` is true, all non-`LOADED` statuses throw a typed `ContextValidationException` with a clear, user-facing message.

The existing `ContextLoader.load(File): String` can remain temporarily as a compatibility wrapper around the new API because the repository currently has few call sites.

## CLI Behavior

Add shared `--require-context` support to `Verity` and add `@SerialName("require-context") val requireContext: Boolean? = null` to `VerityConfig`.

`verity run` validates project context after config and journey resolution but before device connection and LLM setup. This keeps failures fast and avoids touching external systems when the run cannot proceed.

Run output should explicitly report project context state:

- `Project context: optional, not configured`
- `Project context: optional, missing directory: <path>`
- `Project context: optional, no markdown files found in: <path>`
- `Project context: loaded N file(s)`

When files are loaded, print each loaded path in deterministic order. Prefer paths relative to the working directory when possible; otherwise print absolute paths.

When required context is missing, malformed, or empty, fail with the `ContextValidationException` message before opening a device session.

## MCP Behavior

Pass the resolved required-context setting into `VerityMcpServer`.

`get_context` continues to return bundled defaults plus optional project context. Its response should also include loaded project-context file metadata before the combined context text. Optional missing context remains non-fatal and explicit. Required missing context returns a tool error with the same clear validation message used by the CLI.

The `get_context` `path` argument remains supported. If supplied, it is validated using the same required/optional setting as the server-level context path.

## Tests

Core tests:

- Optional `null` directory returns `NOT_CONFIGURED`.
- Optional missing/non-directory path returns `MISSING_DIRECTORY`.
- Optional empty directory returns `EMPTY_DIRECTORY`.
- Required missing, non-directory, and empty directory throw clear validation errors.
- Markdown and `.markdown` files load in deterministic order and report loaded files.
- Non-markdown files remain ignored.

CLI tests:

- `VerityConfig` parses `require-context`.
- Help output includes `--require-context`.
- Required context failure occurs before device connection where practical to test.

MCP tests:

- `get_context` reports bundled defaults when no path is configured and context is optional.
- `get_context` reports loaded project files when context exists.
- `get_context` returns explicit optional missing context when optional.
- `get_context` returns an error when context is required and missing.

## Documentation

Update `docs/architecture.md` to document:

- `--require-context`
- `require-context` config
- project context validation statuses
- `get_context` loaded-file reporting

## Implementation Notes

Blocking file I/O should be wrapped in `Dispatchers.IO` at CLI and MCP call sites. The core loader can stay synchronous, matching its current shape.

Use assertk in tests. Run `./gradlew spotlessApply` before final verification, then `./gradlew check`.
