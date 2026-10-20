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

Required context validates file presence. Content is validated only for [key actions](#key-actions); a blank Markdown file still yields `LOADED`. File-read errors propagate; optional context does not suppress I/O errors.

## Key actions

A key action maps a name to a device key press. Declare them in fenced `verity-key-actions` YAML blocks inside any project-context Markdown file; a file may hold several blocks. Each entry maps a name to a scalar keycode (a short press) or a map with a required `keycode` and an optional boolean `long-press` (default `false`):

````markdown
```verity-key-actions
Open Quick Actions: 174
Hold Quick Actions: { keycode: 174, long-press: true }
```
````

A block opens on a line whose trimmed text is exactly ```` ```verity-key-actions ```` and closes on the next line whose trimmed text is exactly ```` ``` ````; other fences are not tracked, so a fence nested in a block closes it. A blank block yields no entries. The text sent to models is unchanged and still contains the blocks.

**Matching.** A journey instruction resolves when the whole instruction, as split on `;`, equals a name ignoring case and surrounding whitespace. There is no fuzzy or partial matching, so an instruction that only mentions a name goes to the navigator. A match becomes `KeyCode(keycode, longPress)` without a model call, ahead of the platform's built-in phrases, and from there follows the [raw keycode path](structured-actions.md#raw-keycodes). It resolves identically in action groups, loop bodies and [dry run](dry-run.md).

**Validation.** Blocks are parsed whenever context loads (status `LOADED`), even when context is optional. Any error fails setup with exit `3` before preflight, device connection or model construction, and names `<file name>:<line>`. Errors:

- an unclosed block, reported at its opening fence
- invalid YAML, a non-map root, or an entry that is neither a scalar keycode nor a `keycode`/`long-press` map (null and list values included)
- an unknown map key, or a missing `keycode`
- a keycode that is not a non-negative integer that fits in `Int` (`-1`, `1.5`, `0x1A` and overflow are rejected; a quoted `"174"` is accepted), or a `long-press` that is not `true` or `false`
- an empty name, or a name containing `;`
- a duplicate name, ignoring case and trimming, within or across files; the error names both locations
- a name that the run's platform `InteractionMapper` already maps, such as `Press back` or `press keycode 5`. Built-in phrases cannot be overridden. The check uses the journey or suite platform, which a suite shares.

When at least one key action loads, status output in run and dry run, and the MCP `get_context` metadata, add a `Key actions: N` line after the loaded-file lines. Nothing is added otherwise. iOS has no raw keycodes: a step that resolves to a key action is rejected with `UNSUPPORTED_INTERACTION` before device work, and dry run exits `3`.

## CLI behavior

The directory resolves from `--context-path`, then `paths.context`. Required context resolves from `--require-context` or `require-context: true`, with optional context as the default.

Normal `run` loads and validates project context before device connection and LLM construction. Required-context failure is a setup failure. A [dry run](dry-run.md) also validates context before any action generation. Key-action errors are setup failures too. Output reports the status, or the number and ordered paths of loaded files, using paths relative to the working directory where possible.

Bundled context remains separately available unless `--no-bundled-context` is supplied. Bundled guidance does not satisfy a requirement for project context.

## MCP behavior

`get_context` resolves its `path` argument ahead of the server default and applies the server's required-context setting. Successful responses include project-context metadata followed by bundled and project text. Required-context validation failures are tool errors.

`get_context` applies the same key-action parsing and duplicate checks, returns failures as tool errors and adds `Key actions: N` to its metadata. It has no platform, so it skips the built-in collision check.

An optional missing or empty project directory is non-fatal when usable bundled text remains. If both bundled and project text are blank, the tool returns an error rather than a context payload. Loaded file metadata uses absolute paths.

The CLI command that starts MCP validates a configured context directory before starting the server, even when project context is optional. Optional missing-directory handling still applies to `get_context` path arguments and directly constructed servers.

## Source and coverage

- [ContextLoader](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/context/ContextLoader.kt) and [ContextLoaderTest](../../verity/core/src/test/kotlin/me/chrisbanes/verity/core/context/ContextLoaderTest.kt): statuses, loaded-file ordering, and required validation.
- [KeyActions](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/context/KeyActions.kt), [KeyActionParserTest](../../verity/core/src/test/kotlin/me/chrisbanes/verity/core/context/KeyActionParserTest.kt) and [KeyActionInteractionMapper](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/interaction/KeyActionInteractionMapper.kt): parsing, validation and matching.
- [RunCommand](../../verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/RunCommand.kt): validation and console metadata.
- [VerityMcpServer](../../verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt) and [VerityMcpServerTest](../../verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt): tool errors, bundled guidance, and file metadata.
