import com.github.jengelman.gradle.plugins.shadow.tasks.MinimizeTool
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer

plugins {
  id("verity.kotlin-jvm")
  application
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.shadow)
}

application {
  mainClass.set("me.chrisbanes.verity.cli.VerityKt")
}

tasks.withType<ShadowJar>().configureEach {
  archiveBaseName.set("verity")
  manifest.attributes["Main-Class"] = application.mainClass.get()
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
  exclude("module-info.class", "META-INF/versions/**/module-info.class", "META-INF/INDEX.LIST")
  isZip64 = true
  mergeServiceFiles()
  transform<Log4j2PluginsCacheFileTransformer>()
  // Preserve every binary Log4j plugin cache for merging, alongside service/module metadata.
  filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module", "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat")) {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
  }
  failOnDuplicateEntries = true
  // The fat JAR exceeds the remote cache upload limit; keep caching compilation and tests.
  outputs.doNotCacheIf("The fat JAR exceeds the remote build cache upload limit") { true }
  // Exclude POM-only artifacts that have no JAR (Shadow cannot expand them as ZIPs)
  dependencies {
    exclude(dependency("org.graalvm.js:js-community"))
  }
}

tasks.shadowJar { archiveClassifier.set("") }

fun hostJar(host: HostPackaging.Host) = tasks.register<ShadowJar>(
  if (host == HostPackaging.Host.MACOS_ARM64) "macosArm64Jar" else "linuxX64Jar",
) {
  archiveClassifier.set(host.classifier)
  from(sourceSets.main.map { it.output })
  configurations = listOf(project.configurations.runtimeClasspath.get())
  manifest.from(tasks.jar.get().manifest)
  exclude { !HostPackaging.retain(it.path, host) }
}

val macosArm64Jar = hostJar(HostPackaging.Host.MACOS_ARM64)
val linuxX64Jar = hostJar(HostPackaging.Host.LINUX_X64)
val hostJars = tasks.register("hostJars") { dependsOn(tasks.shadowJar, macosArm64Jar, linuxX64Jar) }

// R8 shrinks the universal archive once; host filtering selects resources only, so host variants derive from it.
val r8Configuration = layout.buildDirectory.file("reports/r8/configuration.txt")
val universalShrunkJar = tasks.register<ShadowJar>("universalShrunkJar") {
  destinationDirectory.set(layout.buildDirectory.dir("libs/shrunk"))
  archiveClassifier.set("")
  from(sourceSets.main.map { it.output })
  configurations = listOf(project.configurations.runtimeClasspath.get())
  manifest.from(tasks.jar.get().manifest)
  javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
  // Verity's own modules stay whole through r8-rules.pro; minimize { exclude(project(...)) } would also keep
  // every transitive dependency of those modules, which is nearly the whole archive.
  minimize {
    r8 {
      keepRuleFiles.from("r8-rules.pro")
      keepRules.add(r8Configuration.map { "-printconfiguration ${it.asFile.absolutePath}" })
    }
  }
  outputs.file(r8Configuration)
}

fun shrunkHostJar(host: HostPackaging.Host) = tasks.register<Zip>(
  if (host == HostPackaging.Host.MACOS_ARM64) "macosArm64ShrunkJar" else "linuxX64ShrunkJar",
) {
  // Match the host ShadowJar archive settings.
  destinationDirectory.set(layout.buildDirectory.dir("libs/shrunk"))
  archiveBaseName.set("verity")
  archiveClassifier.set(host.classifier)
  archiveExtension.set("jar")
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
  isZip64 = true
  outputs.doNotCacheIf("The fat JAR exceeds the remote build cache upload limit") { true }
  from(universalShrunkJar.map { zipTree(it.archiveFile) })
  exclude { !HostPackaging.retain(it.path, host) }
}

val macosArm64ShrunkJar = shrunkHostJar(HostPackaging.Host.MACOS_ARM64)
val linuxX64ShrunkJar = shrunkHostJar(HostPackaging.Host.LINUX_X64)
val shrunkJars = tasks.register("shrunkJars") { dependsOn(universalShrunkJar, macosArm64ShrunkJar, linuxX64ShrunkJar) }

dependencies {
  shadowR8(libs.r8)
  implementation(enforcedPlatform(libs.grpc.bom))
  testImplementation(enforcedPlatform(libs.grpc.bom))
  testImplementation(libs.mcp.kotlin.sdk)
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

// Other projects consume this artifact through a variant, without cross-project model access.
val packagedUniversal = configurations.create("packagedUniversal") {
  isCanBeConsumed = true
  isCanBeResolved = false
}
artifacts.add(packagedUniversal.name, tasks.shadowJar)
// Host artifacts use the same isolated-project boundary as the universal archive.
val packagedMacosArm64 = configurations.create("packagedMacosArm64") {
  isCanBeConsumed = true
  isCanBeResolved = false
}
val packagedLinuxX64 = configurations.create("packagedLinuxX64") {
  isCanBeConsumed = true
  isCanBeResolved = false
}
artifacts.add(packagedMacosArm64.name, macosArm64Jar)
artifacts.add(packagedLinuxX64.name, linuxX64Jar)
// Shrunk counterparts; the unshrunk archives above remain the comparison baseline and recovery path.
for ((name, task) in listOf("packagedUniversalShrunk" to universalShrunkJar, "packagedMacosArm64Shrunk" to macosArm64ShrunkJar, "packagedLinuxX64Shrunk" to linuxX64ShrunkJar)) {
  configurations.create(name) {
    isCanBeConsumed = true
    isCanBeResolved = false
  }
  artifacts.add(name, task)
}
val runtime = configurations.runtimeClasspath
val verifyPackagedGrpc = tasks.register<VerifyPackagedGrpc>("verifyPackagedGrpc") {
  dependsOn(tasks.shadowJar, ":verity:smoke-tests:verifySmokeGrpc")
  archive.set(tasks.shadowJar.flatMap { it.archiveFile })
  grpcArtifacts.from(
    runtime.map { configuration ->
      configuration.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.group == "io.grpc" }.map { it.file }
    },
  )
  coordinates.set(
    runtime.map { configuration ->
      configuration.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.group == "io.grpc" }
        .map { "${it.moduleVersion.id.name}:${it.moduleVersion.id.version}:${it.file.name}" }
    },
  )
  receipt.set(layout.buildDirectory.file("reports/packaged-grpc.txt"))
}

// Keep module regressions and separately exercise actual archives with fixture output only.
tasks.test {
  (options as JUnitPlatformOptions).excludeTags("qualification-codex")
  val testRuntimeClasspath = sourceSets.test.get().runtimeClasspath
  // Resolve at execution: resolving while tasks are realized lets another Test task mutate a resolved classpath.
  jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dverity.cli.test.classpath=${testRuntimeClasspath.asPath}") })
  dependsOn(hostJars, shrunkJars)
  val archives = files(
    tasks.shadowJar.flatMap { it.archiveFile }, macosArm64Jar.flatMap { it.archiveFile }, linuxX64Jar.flatMap { it.archiveFile },
    universalShrunkJar.flatMap { it.archiveFile }, macosArm64ShrunkJar.flatMap { it.archiveFile }, linuxX64ShrunkJar.flatMap { it.archiveFile },
  )
  inputs.files(archives).withPropertyName("packagedLoggingArchives")
  systemProperty("verity.cli.packaged.jars", archives.asPath)
  systemProperty("verity.cli.fixture.classes", sourceSets.test.get().output.classesDirs.asPath)
}

// Opt-in real Codex qualification; makes real model requests, so it is never part of check.
tasks.register<Test>("codexQualification") {
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  // The convention plugin selects JUnit Platform.
  (options as JUnitPlatformOptions).includeTags("qualification-codex")
  val properties = mapOf(
    "binary" to providers.gradleProperty("codexQualificationBinary"),
    "version" to providers.gradleProperty("codexQualificationVersion"),
    "priorRequests" to providers.gradleProperty("codexQualificationPriorRequests"),
  )
  properties.forEach { (name, value) ->
    inputs.property(name, value.orElse(""))
    systemProperty("verity.codex.qualification.$name", value.getOrElse(""))
  }
  systemProperty("verity.codex.qualification.reports", layout.buildDirectory.dir("reports/codex-qualification").get().asFile.absolutePath)
  systemProperty("java.awt.headless", "true")
  outputs.upToDateWhen { false }
}

val verifyHostJars = tasks.register<VerifyHostJars>("verifyHostJars") {
  dependsOn(hostJars)
  universal.set(tasks.shadowJar.flatMap { it.archiveFile })
  macos.set(macosArm64Jar.flatMap { it.archiveFile })
  linux.set(linuxX64Jar.flatMap { it.archiveFile })
  runtimeArtifacts.from(runtime.map { it.resolvedConfiguration.resolvedArtifacts.map { artifact -> artifact.file } })
  coordinates.set(
    runtime.map { configuration ->
      configuration.resolvedConfiguration.resolvedArtifacts.map {
        "${it.moduleVersion.id}|${it.classifier ?: ""}|${it.file.name}"
      }
    },
  )
  receipt.set(layout.buildDirectory.file("reports/host-packaging.tsv"))
}
val verifyShrunkJars = tasks.register<VerifyShrunkJars>("verifyShrunkJars") {
  dependsOn(hostJars, shrunkJars)
  universal.set(tasks.shadowJar.flatMap { it.archiveFile })
  macos.set(macosArm64Jar.flatMap { it.archiveFile })
  linux.set(linuxX64Jar.flatMap { it.archiveFile })
  universalShrunk.set(universalShrunkJar.flatMap { it.archiveFile })
  macosShrunk.set(macosArm64ShrunkJar.flatMap { it.archiveFile })
  linuxShrunk.set(linuxX64ShrunkJar.flatMap { it.archiveFile })
  rules.set(layout.projectDirectory.file("r8-rules.pro"))
  configuration.set(r8Configuration)
  mainClass.set(application.mainClass)
  receipt.set(layout.buildDirectory.file("reports/shrunk-packaging.tsv"))
}
tasks.check { dependsOn(verifyHostJars, verifyPackagedGrpc, verifyShrunkJars) }

// Release input generation is offline and consumes only the three verified archives.
val releaseScript = layout.projectDirectory.file("../../scripts/release_artifacts.py")
val releaseDirectory = layout.buildDirectory.dir("release")
val releaseVersion = providers.gradleProperty("version").orElse(project.version.toString())
tasks.register<Exec>("packageRelease") {
  dependsOn(verifyHostJars, verifyPackagedGrpc)
  inputs.file(releaseScript)
  inputs.files(tasks.shadowJar.flatMap { it.archiveFile }, macosArm64Jar.flatMap { it.archiveFile }, linuxX64Jar.flatMap { it.archiveFile })
  inputs.property("releaseVersion", releaseVersion)
  outputs.dir(releaseDirectory)
  commandLine(
    "python3", releaseScript.asFile.absolutePath, "build", "--version", releaseVersion.get(),
    "--output", releaseDirectory.get().asFile.absolutePath,
    tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,
    macosArm64Jar.get().archiveFile.get().asFile.absolutePath,
    linuxX64Jar.get().archiveFile.get().asFile.absolutePath,
  )
}
