# Development Guide for AI Agents

## Build

Use `./gradlew` for all Gradle commands.
Tasks are only complete once `./gradlew check` is green.

### Code Style (Spotless)

- **Check formatting**: `./gradlew spotlessCheck` - Verifies code style without modifying files
- **Apply formatting**: `./gradlew spotlessApply` - Automatically fixes code style issues
- Spotless runs on Kotlin, Kotlin Gradle scripts, and other source files
- CI/CD will fail if code doesn't pass `spotlessCheck`
- Always run `spotlessApply` before committing code
- Spotless is configured at the root level and applies to all modules

## Testing

- Use assertk for Kotlin assertions.
- Run tests with `./gradlew test`, full checks with `./gradlew check`.
- Use `kotlinx-coroutines-test` and `runTest {}` for coroutine tests.

### Test Structure

- **Unit tests**: `src/test/kotlin/` in each module
- **Test resources**: `src/test/resources/` for fixture data (e.g., YAML journey files)

## Project setup

- Modules follow the `:verity:<module>` naming convention:
  - `:verity:core` - Data models, parsing, segmentation, key mapping, hierarchy rendering (zero device/LLM deps)
  - `:verity:device` - Device abstraction layer (Android TV, Android Mobile, iOS)
  - `:verity:agent` - LLM agent orchestration
  - `:verity:mcp` - MCP server for IDE/tool integration
  - `:verity:cli` - CLI entry point
- JVM 21 via toolchain, configured in `verity/build.gradle.kts`
- Kotlin serialization for data models; Kaml for YAML parsing
- DI and framework patterns will be established as modules are implemented

### Serialization DTOs

- DTO property names must use Kotlin camelCase with `@SerialName` for the wire format:
  ```kotlin
  @Serializable
  data class ExampleDto(
    @SerialName("access_token") val accessToken: String,
  )
  ```
- Never use snake_case for Kotlin property names, even when the JSON wire format is snake_case.

## Harness

- Prefer fd to grep
