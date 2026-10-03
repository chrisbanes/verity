# Project context

Project context provides application guidance alongside Verity's bundled automation guidance. This describes implemented behavior originating in the July 2026 design for [issue #47](https://github.com/chrisbanes/verity/issues/47). The repository's [domain glossary](../../CONTEXT.md) is separate from runtime project context.

## Loading contract

Project context is loaded from a configured directory's immediate regular files with lowercase `.md` or `.markdown` extensions. Other files and nested directories are ignored. Files are sorted by filename; their trimmed contents are joined with blank lines. The result includes the combined text, loaded-file list, and status.

| Status | Condition | Optional context | Required context |
| --- | --- | --- | --- |
| `NOT_CONFIGURED` | No directory supplied | Empty project text, explicit status | Validation failure |
| `MISSING_DIRECTORY` | Path is missing or is not a directory | Empty project text, explicit status | Validation failure |
| `EMPTY_DIRECTORY` | No matching Markdown files | Empty project text, explicit status | Validation failure |
| `LOADED` | At least one matching file | Text and file metadata | Text and file metadata |

Required context validates file presence, not the content of those files. A blank Markdown file still yields `LOADED`. File-read errors propagate; optional context does not suppress I/O errors.

## CLI behavior

The directory resolves from `--context-path`, then `paths.context`. Required context resolves from `--require-context` or `require-context: true`, with optional context as the default.

Normal `run` loads and validates project context before device connection and LLM construction. Required-context failure is a setup failure. A [dry run](dry-run.md) also validates context before any action generation. Output reports the status, or the number and ordered paths of loaded files, using paths relative to the working directory where possible.

Bundled context remains separately available unless `--no-bundled-context` is supplied. Bundled guidance does not satisfy a requirement for project context.

## MCP behavior

`get_context` resolves its `path` argument ahead of the server default and applies the server's required-context setting. Successful responses include project-context metadata followed by bundled and project text. Required-context validation failures are tool errors.

An optional missing or empty project directory is non-fatal when usable bundled text remains. If both bundled and project text are blank, the tool returns an error rather than a context payload. Loaded file metadata uses absolute paths.

The CLI command that starts MCP validates a configured context directory before starting the server, even when project context is optional. Optional missing-directory handling still applies to `get_context` path arguments and directly constructed servers.

## Source and coverage

- [ContextLoader](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/context/ContextLoader.kt) and [ContextLoaderTest](../../verity/core/src/test/kotlin/me/chrisbanes/verity/core/context/ContextLoaderTest.kt): statuses, loaded-file ordering, and required validation.
- [RunCommand](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt): validation and console metadata.
- [VerityMcpServer](../../verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt) and [VerityMcpServerTest](../../verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt): tool errors, bundled guidance, and file metadata.
