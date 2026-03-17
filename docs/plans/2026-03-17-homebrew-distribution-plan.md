# Homebrew Distribution Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Distribute Verity as a Homebrew-installable CLI via shadow JAR and GitHub Actions release workflow.

**Architecture:** Shadow plugin produces a fat JAR. GitHub Actions release workflow builds it on tag push, creates a GitHub Release, and updates the formula in `chrisbanes/homebrew-tap`. The formula source of truth lives in this repo.

**Tech Stack:** Gradle Shadow plugin 9.4.0, GitHub Actions, Homebrew Ruby formula

---

### Task 1: Add Shadow Plugin to Version Catalog

**Files:**
- Modify: `gradle/libs.versions.toml`

**Step 1: Add shadow plugin version and plugin entry**

Add to `[versions]`:
```toml
shadow = "9.4.0"
```

Add to `[plugins]`:
```toml
shadow = { id = "com.gradleup.shadow", version.ref = "shadow" }
```

**Step 2: Verify the catalog parses**

Run: `./gradlew help`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "build: add Shadow plugin to version catalog"
```

---

### Task 2: Configure Shadow JAR in CLI Module

**Files:**
- Modify: `verity/cli/build.gradle.kts`

**Step 1: Apply the shadow plugin and configure it**

Replace the entire file with:

```kotlin
plugins {
  application
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.shadow)
}

application {
  mainClass.set("me.chrisbanes.verity.cli.VerityKt")
}

tasks.shadowJar {
  archiveBaseName.set("verity")
  archiveClassifier.set("")
  mergeServiceFiles()
}

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

  testImplementation(testFixtures(project(":verity:device")))
  testImplementation(testFixtures(project(":verity:agent")))
}
```

Key decisions:
- `archiveClassifier.set("")` removes the `-all` suffix so the JAR is just `verity-0.1.0.jar`
- `mergeServiceFiles()` handles META-INF/services for gRPC, Ktor, and other ServiceLoader-based libraries

**Step 2: Run spotlessApply**

Run: `./gradlew spotlessApply`

**Step 3: Build the shadow JAR**

Run: `./gradlew :verity:cli:shadowJar`
Expected: BUILD SUCCESSFUL. Output JAR at `verity/cli/build/libs/verity-0.1.0.jar`

**Step 4: Smoke test the fat JAR**

Run: `java -jar verity/cli/build/libs/verity-0.1.0.jar --help`
Expected: Prints Verity CLI help text (usage, subcommands: run, list, mcp)

**Step 5: Run full checks**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add verity/cli/build.gradle.kts
git commit -m "build: configure Shadow JAR for CLI module"
```

---

### Task 3: Create Homebrew Formula

**Files:**
- Create: `Formula/verity.rb`

**Step 1: Write the formula**

```ruby
class Verity < Formula
  desc "LLM-powered E2E testing for mobile and TV"
  homepage "https://github.com/chrisbanes/verity"
  url "https://github.com/chrisbanes/verity/releases/download/v#{version}/verity-#{version}.jar"
  sha256 "PLACEHOLDER"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install "verity-#{version}.jar" => "verity.jar"

    (bin/"verity").write <<~BASH
      #!/bin/bash
      export JAVA_HOME="#{Formula["openjdk@21"].opt_prefix}"
      exec "${JAVA_HOME}/bin/java" -jar "#{libexec}/verity.jar" "$@"
    BASH
  end

  test do
    assert_match "verity", shell_output("#{bin}/verity --help")
  end
end
```

Note: `sha256` and `url` are placeholders. The release workflow updates them.

**Step 2: Commit**

```bash
git add Formula/verity.rb
git commit -m "build: add Homebrew formula"
```

---

### Task 4: Create GitHub Actions Release Workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Step 1: Write the release workflow**

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v4

      - name: Build shadow JAR
        run: ./gradlew :verity:cli:shadowJar

      - name: Get version from tag
        id: version
        run: echo "version=${GITHUB_REF_NAME#v}" >> "$GITHUB_OUTPUT"

      - name: Compute SHA256
        id: sha
        run: |
          JAR_PATH="verity/cli/build/libs/verity-${{ steps.version.outputs.version }}.jar"
          SHA=$(shasum -a 256 "$JAR_PATH" | awk '{print $1}')
          echo "sha256=$SHA" >> "$GITHUB_OUTPUT"
          echo "jar_path=$JAR_PATH" >> "$GITHUB_OUTPUT"

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: ${{ steps.sha.outputs.jar_path }}
          generate_release_notes: true

      - name: Update Homebrew formula
        uses: actions/checkout@v4
        with:
          repository: chrisbanes/homebrew-tap
          token: ${{ secrets.HOMEBREW_TAP_TOKEN }}
          path: homebrew-tap

      - name: Write updated formula
        run: |
          VERSION="${{ steps.version.outputs.version }}"
          SHA="${{ steps.sha.outputs.sha256 }}"
          sed -e "s/PLACEHOLDER/$SHA/" \
              -e "s|url \".*\"|url \"https://github.com/chrisbanes/verity/releases/download/v${VERSION}/verity-${VERSION}.jar\"|" \
              Formula/verity.rb > homebrew-tap/Formula/verity.rb

      - name: Push formula to tap
        working-directory: homebrew-tap
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add Formula/verity.rb
          git commit -m "verity ${{ steps.version.outputs.version }}"
          git push
```

Note: Requires a `HOMEBREW_TAP_TOKEN` repository secret — a GitHub PAT with `repo` scope for `chrisbanes/homebrew-tap`.

**Step 2: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: add release workflow for Homebrew distribution"
```

---

### Task 5: End-to-End Validation

**Step 1: Build and smoke test the shadow JAR locally**

Run: `./gradlew :verity:cli:shadowJar && java -jar verity/cli/build/libs/verity-0.1.0.jar --help`
Expected: Prints help text

**Step 2: Run full check suite**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL

**Step 3: Verify formula syntax (if `brew` is available)**

Run: `brew style Formula/verity.rb` (optional — only if Homebrew is installed)
Expected: No offenses detected

**Step 4: Commit any fixes and final cleanup**

```bash
git add -A
git commit -m "build: finalize Homebrew distribution setup"
```

---

## Post-Implementation Notes

- **First release:** Tag with `git tag v0.1.0 && git push origin v0.1.0` to trigger the workflow.
- **HOMEBREW_TAP_TOKEN:** Create a fine-grained PAT with `Contents: Read and Write` permission scoped to `chrisbanes/homebrew-tap`. Add as a repository secret in `chrisbanes/verity`.
- **License:** The formula assumes Apache-2.0. Update if different.
