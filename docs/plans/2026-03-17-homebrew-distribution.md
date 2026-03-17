# Homebrew Distribution

Distribute Verity as a Homebrew-installable CLI tool.

## Architecture

```
git tag v0.1.0
  → GitHub Actions builds shadow JAR
  → Uploads JAR + SHA256 to GitHub Release
  → Updates formula in chrisbanes/homebrew-tap

brew tap chrisbanes/tap
brew install verity
verity run journey.yaml
```

## Components

### 1. Shadow JAR

Add the [Gradle Shadow plugin](https://github.com/GradleUp/shadow) to `:verity:cli`.
Produces a single fat JAR containing all dependencies.
Output: `verity-<version>-all.jar`.

### 2. Homebrew Formula

Source of truth lives in this repo at `Formula/verity.rb`.
Release workflow copies it to `chrisbanes/homebrew-tap`.

The formula:
- Declares `depends_on "openjdk@21"`
- Downloads the shadow JAR from the GitHub Release
- Verifies SHA256
- Installs a shell wrapper that sets `JAVA_HOME` and runs `java -jar verity.jar`

### 3. GitHub Actions Release Workflow

Triggered on tags matching `v*`. Steps:

1. Check out the tagged commit
2. Run `./gradlew :verity:cli:shadowJar`
3. Compute SHA256 of the JAR
4. Create a GitHub Release with the JAR attached
5. Update the formula in `chrisbanes/homebrew-tap` with the new version URL and SHA256

### 4. Tap Repository

`chrisbanes/homebrew-tap` (already exists) receives the updated formula on each release.
Users install with:

```bash
brew tap chrisbanes/tap
brew install verity
```

## Files to Create or Modify

| File | Action |
|------|--------|
| `verity/cli/build.gradle.kts` | Add Shadow plugin, configure `shadowJar` task |
| `Formula/verity.rb` | Homebrew formula (source of truth) |
| `.github/workflows/release.yml` | Release workflow |

## Command Name

`verity`

## Runtime Requirements

- JDK 21 (installed by Homebrew via `depends_on "openjdk@21"`)
- ADB on PATH (for Android device testing)
- LLM provider API key (e.g. `ANTHROPIC_API_KEY`)
