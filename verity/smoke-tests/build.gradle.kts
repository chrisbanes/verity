import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

plugins {
  id("verity.kotlin-jvm")
}

dependencies {
  implementation(enforcedPlatform(libs.grpc.bom))
  testImplementation(enforcedPlatform(libs.grpc.bom))
  testImplementation(project(":verity:core"))
  testImplementation(project(":verity:device"))
  testImplementation(testFixtures(project(":verity:device")))
  testImplementation(project(":verity:agent"))
  testImplementation(testFixtures(project(":verity:agent")))
  testImplementation(libs.kotlinx.serialization.json)
  testImplementation(libs.dadb)
  testImplementation(libs.maestro.client)
  testImplementation(libs.grpc.netty.shaded)
  testImplementation(libs.grpc.stub)
  testImplementation(libs.mcp.kotlin.sdk)
  testImplementation("io.ktor:ktor-client-cio:${libs.versions.ktor.get()}")
  testImplementation(libs.koog.agents)
  // The provider probe compiles against production client classes; its child JVM loads them from the packaged JAR.
  testImplementation(libs.koog.anthropic)
  testImplementation(libs.koog.openai)
  testImplementation(libs.koog.google)
  testImplementation(libs.koog.openrouter)
  testImplementation(libs.koog.bedrock)
  testImplementation(libs.koog.deepseek)
  testImplementation(libs.koog.mistral)
  testImplementation(libs.koog.ollama)
  testImplementation(libs.koog.dashscope)
  testCompileOnly(project(":verity:cli"))
}

tasks.test {
  maxHeapSize = "512m"
  // Device smoke tests require -Pinclude.tags=android or -Pinclude.tags=ios.
  // Without tags, only untagged tests (e.g. JourneyLoadTest) run.
  val includeTags = providers.gradleProperty("include.tags")
  // Simulator selection affects the test result, but the runner's ephemeral UDID does not.
  inputs.property("iosSimulatorModel", providers.environmentVariable("VERITY_SMOKE_IOS_MODEL").orElse(""))
  inputs.property("iosSimulatorRuntime", providers.environmentVariable("VERITY_SMOKE_IOS_RUNTIME").orElse(""))
  for (name in listOf(
    "VERITY_QUALIFICATION_ANDROID_SERIAL",
    "VERITY_QUALIFICATION_IOS_UDID",
    "VERITY_QUALIFICATION_TARGET_RECEIPT",
    "VERITY_QUALIFICATION_EVENTS",
    "VERITY_QUALIFICATION_RUN_ID",
    "VERITY_QUALIFICATION_HEAD",
  )) {
    inputs.property(name, providers.environmentVariable(name).orElse(""))
  }
  providers.environmentVariable("IOS_SIMULATOR_UDID").orNull?.let {
    inputs.property("jobOwnedIosUdid", it)
    systemProperty("verity.smoke.ios.udid", it)
  }
  providers.environmentVariable("VERITY_QUALIFICATION_TARGET_RECEIPT").orNull?.let { receipt ->
    inputs.file(receipt).withPropertyName("qualificationTargetReceiptContents")
  }
  useJUnitPlatform {
    val tags = includeTags.orNull
    if (tags != null) {
      includeTags(tags)
    } else {
      excludeTags("android", "ios", "qualification-android", "qualification-ios", "packaged-android", "packaged-ios")
    }
  }
}

// Probe classes also run beside the R8-shrunk CLI, which rewrites its own Kotlin null-check intrinsics and drops
// the unused Intrinsics methods. Compile test code without those intrinsic calls so the probe links.
tasks.compileTestKotlin {
  compilerOptions.freeCompilerArgs.addAll("-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions")
}

configurations.configureEach {
  exclude(group = "io.grpc", module = "grpc-netty")
}

val packagedRuntime = configurations.create("packagedRuntime") {
  isCanBeConsumed = false
  isCanBeResolved = true
}
dependencies {
  add(packagedRuntime.name, project(path = ":verity:cli", configuration = "packagedUniversal"))
}
val universalJar = packagedRuntime.elements.map { it.single().asFile }
val packagedRuntimeShrunk = configurations.create("packagedRuntimeShrunk") {
  isCanBeConsumed = false
  isCanBeResolved = true
}
dependencies {
  add(packagedRuntimeShrunk.name, project(path = ":verity:cli", configuration = "packagedUniversalShrunk"))
}
val universalShrunkJar = packagedRuntimeShrunk.elements.map { it.single().asFile }

val smokeRuntime = configurations.testRuntimeClasspath
val verifySmokeGrpc = tasks.register<VerifyPackagedGrpc>("verifySmokeGrpc") {
  grpcArtifacts.from(
    smokeRuntime.map { configuration ->
      configuration.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.group == "io.grpc" }.map { it.file }
    },
  )
  coordinates.set(
    smokeRuntime.map { configuration ->
      configuration.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.group == "io.grpc" }
        .map { "${it.moduleVersion.id.name}:${it.moduleVersion.id.version}:${it.file.name}" }
    },
  )
  receipt.set(layout.buildDirectory.file("reports/smoke-grpc.txt"))
}
val packagedProbeJar = tasks.register<Jar>("packagedProbeJar") {
  dependsOn(tasks.testClasses)
  archiveClassifier.set("packaged-probe")
  from(sourceSets.test.get().output.classesDirs) {
    include("me/chrisbanes/verity/smoke/PackagedRuntimeProbe*.class")
    include("me/chrisbanes/verity/smoke/ProbeCallerCancellation.class")
  }
  doLast {
    ZipFile(archiveFile.get().asFile).use { zip ->
      check(
        zip.entries().asSequence().filter { it.name.endsWith(".class") }.all {
          it.name.startsWith("me/chrisbanes/verity/smoke/PackagedRuntimeProbe") ||
            it.name == "me/chrisbanes/verity/smoke/ProbeCallerCancellation.class"
        },
      ) { "Probe archive contains a production or unrelated test class" }
    }
  }
}
// Offline packaged checks run every probe against both universal archives; shrunk output must match unshrunk.
tasks.test {
  inputs.files(universalJar, universalShrunkJar).withPropertyName("packagedCliOptionArchives")
  inputs.file(packagedProbeJar.flatMap { it.archiveFile }).withPropertyName("packagedProbeJar")
  val archives = universalJar.zip(universalShrunkJar) { unshrunk, shrunk -> listOf(unshrunk, shrunk) }
  val probe = packagedProbeJar.flatMap { it.archiveFile }
  doFirst {
    systemProperty("verity.packaged.cli.options.jars", archives.get().joinToString(File.pathSeparator) { it.absolutePath })
    systemProperty("verity.packaged.probe.jar", probe.get().asFile.absolutePath)
  }
}
val packagedGrpcProbe = tasks.register<JavaExec>("packagedGrpcProbe") {
  dependsOn(":verity:cli:verifyPackagedGrpc", packagedProbeJar)
  classpath(files(universalJar, packagedProbeJar.flatMap { it.archiveFile }))
  mainClass.set("me.chrisbanes.verity.smoke.PackagedRuntimeProbe")
  maxHeapSize = "512m"
  args("grpc")
}

fun packagedHost(name: String, exported: String) = configurations.create(name) {
  isCanBeConsumed = false
  isCanBeResolved = true
}.also { configuration ->
  dependencies.add(configuration.name, dependencies.project(mapOf("path" to ":verity:cli", "configuration" to exported)))
}
val packagedMacos = packagedHost("packagedMacos", "packagedMacosArm64")
val packagedLinux = packagedHost("packagedLinux", "packagedLinuxX64")
val packagedMacosShrunk = packagedHost("packagedMacosShrunk", "packagedMacosArm64Shrunk")
val packagedLinuxShrunk = packagedHost("packagedLinuxShrunk", "packagedLinuxX64Shrunk")
val productionJars = mapOf(
  "universal" to universalJar,
  "macos-aarch64" to packagedMacos.elements.map { it.single().asFile },
  "linux-x86_64" to packagedLinux.elements.map { it.single().asFile },
  "universal-shrunk" to universalShrunkJar,
  "macos-aarch64-shrunk" to packagedMacosShrunk.elements.map { it.single().asFile },
  "linux-x86_64-shrunk" to packagedLinuxShrunk.elements.map { it.single().asFile },
)
val selectedVariants = providers.gradleProperty("packagedVariant").orElse("universal")
val packagedReports = layout.buildDirectory.dir("reports/packaged")
fun packagedTest(name: String, tag: String) = tasks.register<Test>(name) {
  dependsOn(":verity:cli:verifyPackagedGrpc", ":verity:cli:verifyHostJars", ":verity:cli:verifyShrunkJars", packagedProbeJar)
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  maxHeapSize = "512m"
  useJUnitPlatform { includeTags(tag) }
  for ((variant, archive) in productionJars) inputs.file(archive).withPropertyName("productionJar.$variant")
  inputs.file(packagedProbeJar.flatMap { it.archiveFile }).withPropertyName("probeJar")
  inputs.property("packagedVariants", selectedVariants)
  for (name in listOf("ANDROID_SERIAL", "IOS_SIMULATOR_UDID")) {
    inputs.property(name, providers.environmentVariable(name).orElse(""))
  }
  val probeArchive = packagedProbeJar.flatMap { it.archiveFile }
  val archives = productionJars
  val variants = selectedVariants
  val reportDirectory = packagedReports
  doFirst {
    val selected = variants.get().split(',')
    check(selected.isNotEmpty() && selected.distinct().size == selected.size && selected.all { it in archives }) { "Unknown or duplicate packaged variant: $selected" }
    fun digest(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          digest.update(buffer, 0, count)
        }
      }
      return HexFormat.of().formatHex(digest.digest())
    }
    for (variant in selected) {
      val archive = archives.getValue(variant).get()
      check(archive.isFile) { "Missing produced $variant archive" }
      systemProperty("verity.packaged.jar.$variant", archive.absolutePath)
      systemProperty("verity.packaged.sha.$variant", digest(archive))
    }
    val probe = probeArchive.get().asFile
    systemProperty("verity.packaged.variants", selected.joinToString(","))
    systemProperty("verity.packaged.probe", probe.absolutePath)
    systemProperty("verity.packaged.probe.sha", digest(probe))
    systemProperty("verity.packaged.reports", reportDirectory.get().asFile.absolutePath)
  }
}
val packagedAndroidTest = packagedTest("packagedAndroidTest", "packaged-android")
val packagedIosTest = packagedTest("packagedIosTest", "packaged-ios")
