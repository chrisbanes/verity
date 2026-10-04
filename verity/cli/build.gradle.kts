import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

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
  // Let the transformers see every service descriptor and Kotlin module metadata file.
  filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
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

dependencies {
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

// The device-free command regression intentionally uses its module runtime classpath.
tasks.test {
  systemProperty("verity.cli.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
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
tasks.check { dependsOn(verifyHostJars, verifyPackagedGrpc) }

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
