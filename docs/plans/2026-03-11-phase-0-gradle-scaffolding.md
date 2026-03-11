# Phase 0: Gradle Scaffolding — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Set up the Gradle multi-module project structure for Verity with all dependency declarations.

**Architecture:** A root Gradle project containing a `verity/` parent module with five submodules (core, device, agent, mcp, cli). Version catalog in `gradle/libs.versions.toml` centralizes all dependency versions. Each module declares only its own dependencies.

**Tech Stack:** Gradle 8.x, Kotlin 2.1.20, kotlinx.serialization 1.8.0

**Design doc:** `docs/plans/2026-03-11-verity-design.md`

---

### Task 1: Create version catalog

**Files:**
- Create: `gradle/libs.versions.toml`

**Step 1: Verify latest stable dependency versions first**

Before writing `libs.versions.toml`, verify the latest stable versions for **all** entries (`kotlin`, coroutines, serialization, Ktor, Koog, Maestro, MCP SDK, gRPC, etc.) using source registries (Maven Central / Gradle Plugin Portal / project release pages).

Do not copy stale versions blindly from this plan. Treat the block below as a structure/template and refresh versions at implementation time.

**Step 2: Create the version catalog file**

```toml
[versions]
kotlin = "2.1.20"
kotlinx-serialization = "1.8.0"
kotlinx-coroutines = "1.10.1"
kaml = "0.67.0"
koog = "0.6.4"
clikt = "5.1.0"
ktor = "3.1.1"
maestro-client = "2.0.10"
maestro-ios = "2.2.0"
dadb = "1.2.7"
mcp-kotlin = "0.8.3"
grpc = "1.57.2"
grpc-kotlin = "1.4.1"
grpc-netty-shaded = "1.57.2"
assertk = "0.28.1"

[libraries]
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
kaml = { module = "com.charleskorn.kaml:kaml", version.ref = "kaml" }
koog-agents = { module = "ai.koog:koog-agents", version.ref = "koog" }
koog-anthropic = { module = "ai.koog:prompt-executor-anthropic-client", version.ref = "koog" }
clikt = { module = "com.github.ajalt.clikt:clikt", version.ref = "clikt" }
ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
maestro-client = { module = "dev.mobile:maestro-client", version.ref = "maestro-client" }
maestro-ios-driver = { module = "dev.mobile:maestro-ios-driver", version.ref = "maestro-ios" }
dadb = { module = "dev.mobile:dadb", version.ref = "dadb" }
mcp-kotlin-sdk = { module = "io.modelcontextprotocol:kotlin-sdk", version.ref = "mcp-kotlin" }
grpc-netty-shaded = { module = "io.grpc:grpc-netty-shaded", version.ref = "grpc-netty-shaded" }
grpc-stub = { module = "io.grpc:grpc-stub", version.ref = "grpc" }
grpc-protobuf = { module = "io.grpc:grpc-protobuf", version.ref = "grpc" }
grpc-kotlin-stub = { module = "io.grpc:grpc-kotlin-stub", version.ref = "grpc-kotlin" }
assertk = { module = "com.willowtreeapps.assertk:assertk", version.ref = "assertk" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

**Step 3: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "feat: add Gradle version catalog with all dependency versions"
```

---

### Task 2: Create root build and settings files

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`

**Step 1: Create settings.gradle.kts**

```kotlin
rootProject.name = "verity"

include(":verity:core")
include(":verity:device")
include(":verity:agent")
include(":verity:mcp")
include(":verity:cli")
```

**Step 2: Create root build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

**Step 3: Commit**

```bash
git add settings.gradle.kts build.gradle.kts
git commit -m "feat: add root settings and build files with module includes"
```

---

### Task 3: Create verity parent build file

**Files:**
- Create: `verity/build.gradle.kts`

**Step 1: Create the shared build config**

```kotlin
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "me.chrisbanes.verity"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"(rootProject.libs.assertk)
        "testImplementation"(rootProject.libs.kotlinx.coroutines.test)
    }
}
```

**Step 2: Commit**

```bash
git add verity/build.gradle.kts
git commit -m "feat: add shared verity parent build config"
```

---

### Task 4: Create core module build file

**Files:**
- Create: `verity/core/build.gradle.kts`
- Create: `verity/core/src/main/kotlin/me/chrisbanes/verity/core/.gitkeep`
- Create: `verity/core/src/test/kotlin/me/chrisbanes/verity/core/.gitkeep`

**Step 1: Create core build.gradle.kts**

`:verity:core` has zero SDK dependencies — only kotlinx.serialization and Kaml.

```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
    implementation(libs.kotlinx.coroutines.core)
}
```

**Step 2: Create placeholder source directories**

```bash
mkdir -p verity/core/src/main/kotlin/me/chrisbanes/verity/core
mkdir -p verity/core/src/test/kotlin/me/chrisbanes/verity/core
touch verity/core/src/main/kotlin/me/chrisbanes/verity/core/.gitkeep
touch verity/core/src/test/kotlin/me/chrisbanes/verity/core/.gitkeep
```

**Step 3: Commit**

```bash
git add verity/core/
git commit -m "feat: add :verity:core module with serialization dependencies"
```

---

### Task 5: Create device module build file

**Files:**
- Create: `verity/device/build.gradle.kts`
- Create: `verity/device/src/main/kotlin/me/chrisbanes/verity/device/.gitkeep`
- Create: `verity/device/src/test/kotlin/me/chrisbanes/verity/device/.gitkeep`

**Step 1: Create device build.gradle.kts**

`:verity:device` depends on core and the Maestro/Dadb SDKs. This is where the Netty conflict resolution lives.

```kotlin
dependencies {
    implementation(project(":verity:core"))
    implementation(libs.kotlinx.coroutines.core)

    // Android device
    implementation(libs.dadb)
    implementation(libs.maestro.client) {
        exclude(group = "io.grpc", module = "grpc-netty")
    }

    // iOS device
    implementation(libs.maestro.ios.driver)

    // gRPC with shaded Netty to avoid Ktor conflict
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
}

configurations.all {
    resolutionStrategy {
        force("io.grpc:grpc-stub:${libs.versions.grpc.get()}")
        force("io.grpc:grpc-protobuf:${libs.versions.grpc.get()}")
        force("io.grpc:grpc-core:${libs.versions.grpc.get()}")
        force("io.grpc:grpc-api:${libs.versions.grpc.get()}")
        force("io.grpc:grpc-context:${libs.versions.grpc.get()}")
    }
}
```

**Step 2: Create placeholder source directories**

```bash
mkdir -p verity/device/src/main/kotlin/me/chrisbanes/verity/device
mkdir -p verity/device/src/test/kotlin/me/chrisbanes/verity/device
touch verity/device/src/main/kotlin/me/chrisbanes/verity/device/.gitkeep
touch verity/device/src/test/kotlin/me/chrisbanes/verity/device/.gitkeep
```

**Step 3: Commit**

```bash
git add verity/device/
git commit -m "feat: add :verity:device module with Maestro SDK and Dadb dependencies"
```

---

### Task 6: Create agent module build file

**Files:**
- Create: `verity/agent/build.gradle.kts`
- Create: `verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/.gitkeep`
- Create: `verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/.gitkeep`

**Step 1: Create agent build.gradle.kts**

`:verity:agent` depends on core, device, and Koog.

```kotlin
dependencies {
    implementation(project(":verity:core"))
    implementation(project(":verity:device"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koog.agents)
    implementation(libs.koog.anthropic)
}
```

**Step 2: Create placeholder source directories**

```bash
mkdir -p verity/agent/src/main/kotlin/me/chrisbanes/verity/agent
mkdir -p verity/agent/src/test/kotlin/me/chrisbanes/verity/agent
touch verity/agent/src/main/kotlin/me/chrisbanes/verity/agent/.gitkeep
touch verity/agent/src/test/kotlin/me/chrisbanes/verity/agent/.gitkeep
```

**Step 3: Commit**

```bash
git add verity/agent/
git commit -m "feat: add :verity:agent module with Koog LLM dependencies"
```

---

### Task 7: Create MCP module build file

**Files:**
- Create: `verity/mcp/build.gradle.kts`
- Create: `verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/.gitkeep`
- Create: `verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/.gitkeep`

**Step 1: Create mcp build.gradle.kts**

`:verity:mcp` depends on core, device, MCP SDK, and Ktor. Does NOT depend on agent.

```kotlin
dependencies {
    implementation(project(":verity:core"))
    implementation(project(":verity:device"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
}
```

**Step 2: Create placeholder source directories**

```bash
mkdir -p verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp
mkdir -p verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp
touch verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/.gitkeep
touch verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/.gitkeep
```

**Step 3: Commit**

```bash
git add verity/mcp/
git commit -m "feat: add :verity:mcp module with MCP SDK and Ktor dependencies"
```

---

### Task 8: Create CLI module build file

**Files:**
- Create: `verity/cli/build.gradle.kts`
- Create: `verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/.gitkeep`
- Create: `verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/.gitkeep`

**Step 1: Create cli build.gradle.kts**

`:verity:cli` depends on agent and mcp. This is the application entry point.

```kotlin
plugins {
    application
}

application {
    mainClass.set("me.chrisbanes.verity.cli.VerityKt")
}

dependencies {
    implementation(project(":verity:agent"))
    implementation(project(":verity:mcp"))
    implementation(libs.clikt)
}
```

**Step 2: Create placeholder source directories**

```bash
mkdir -p verity/cli/src/main/kotlin/me/chrisbanes/verity/cli
mkdir -p verity/cli/src/test/kotlin/me/chrisbanes/verity/cli
touch verity/cli/src/main/kotlin/me/chrisbanes/verity/cli/.gitkeep
touch verity/cli/src/test/kotlin/me/chrisbanes/verity/cli/.gitkeep
```

**Step 3: Commit**

```bash
git add verity/cli/
git commit -m "feat: add :verity:cli module with Clikt dependency"
```

---

### Task 9: Add Gradle wrapper and verify compilation

**Step 1: Ensure Gradle wrapper is present**

If not already present, generate it:

```bash
gradle wrapper --gradle-version 8.12
```

**Step 2: Verify the project compiles**

```bash
./gradlew :verity:core:compileKotlin :verity:device:compileKotlin :verity:agent:compileKotlin :verity:mcp:compileKotlin :verity:cli:compileKotlin
```

Expected: BUILD SUCCESSFUL (no source files yet, but dependency resolution and configuration should succeed).

**Step 3: Commit wrapper if generated**

```bash
git add gradlew gradlew.bat gradle/
git commit -m "feat: add Gradle wrapper"
```

---

### Task 10: Create skills directory structure

**Files:**
- Create: `verity/skills/context/procedures.md` (empty placeholder)
- Create: `verity/skills/run/SKILL.md` (empty placeholder)
- Create: `verity/skills/author/SKILL.md` (empty placeholder)

Skills are not a Gradle module — just markdown files.

**Step 1: Create directory structure with placeholders**

```bash
mkdir -p verity/skills/context verity/skills/run verity/skills/author
echo "# Shared Procedures" > verity/skills/context/procedures.md
echo "# Verity Run Skill" > verity/skills/run/SKILL.md
echo "# Verity Author Skill" > verity/skills/author/SKILL.md
```

**Step 2: Commit**

```bash
git add verity/skills/
git commit -m "feat: add skills directory structure with placeholders"
```

---

## Verification

After all tasks complete, the project structure should be:

```
verity/
├── build.gradle.kts                     # Root
├── settings.gradle.kts                  # Module includes
├── gradle/
│   └── libs.versions.toml              # Version catalog
├── gradlew
├── gradlew.bat
└── verity/
    ├── build.gradle.kts                 # Shared subproject config
    ├── core/
    │   ├── build.gradle.kts
    │   └── src/{main,test}/kotlin/me/chrisbanes/verity/core/
    ├── device/
    │   ├── build.gradle.kts
    │   └── src/{main,test}/kotlin/me/chrisbanes/verity/device/
    ├── agent/
    │   ├── build.gradle.kts
    │   └── src/{main,test}/kotlin/me/chrisbanes/verity/agent/
    ├── mcp/
    │   ├── build.gradle.kts
    │   └── src/{main,test}/kotlin/me/chrisbanes/verity/mcp/
    ├── cli/
    │   ├── build.gradle.kts
    │   └── src/{main,test}/kotlin/me/chrisbanes/verity/cli/
    └── skills/
        ├── context/procedures.md
        ├── run/SKILL.md
        └── author/SKILL.md
```

Run `./gradlew projects` to confirm all modules are recognized.
