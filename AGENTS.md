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

- Use assertk for Kotlin assertions. Never use `kotlin.assert()` or `kotlin.require()` as test assertions — JVM assertions are no-ops without `-ea`.
- Run tests with `./gradlew test`, full checks with `./gradlew check`.
- Use `kotlinx-coroutines-test` and `runTest {}` for coroutine tests.

## Coroutines

- **CancellationException**: When catching `Exception` in a suspend context, always rethrow `CancellationException` first to preserve structured concurrency:
  ```kotlin
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    // handle error
  }
  ```
- **Blocking I/O**: Wrap `Files.*`, `ProcessBuilder`, and other blocking JVM APIs in `withContext(Dispatchers.IO)`.
- **Cleanup in finally**: Wrap suspend cleanup calls (e.g., `close()`) in `withContext(NonCancellable)` when inside a `finally` block to ensure they complete even on cancellation.

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
- **Dependency Injection / Inversion of Control**: Prefer Constructor Injection. For factories or static dependencies, use "Function Injection" by passing functions as constructor parameters with default values pointing to the real implementation (e.g., `private val sessionFactory: (...) -> DeviceSession = DeviceSessionFactory::connect`). This keeps production call sites clean while making it trivial to inject mock implementations during testing.

### Serialization DTOs

- DTO property names must use Kotlin camelCase with `@SerialName` for the wire format:
  ```kotlin
  @Serializable
  data class ExampleDto(
    @SerialName("access_token") val accessToken: String,
  )
  ```
- Never use snake_case for Kotlin property names, even when the JSON wire format is snake_case.

## Git

- Do not add `Co-Authored-By` or other agent/bot trailers to commit messages. The human author is the sole commit author.

## Architecture

- `docs/architecture.md` is the architecture reference. When you add, remove, or change modules, interfaces, CLI options, MCP tools, or data models, update the architecture doc to match.

## Harness

- Prefer fd to grep
