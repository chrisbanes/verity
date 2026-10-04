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
  providers.environmentVariable("VERITY_QUALIFICATION_TARGET_RECEIPT").orNull?.let { receipt ->
    inputs.file(receipt).withPropertyName("qualificationTargetReceiptContents")
  }
  useJUnitPlatform {
    val tags = includeTags.orNull
    if (tags != null) {
      includeTags(tags)
    } else {
      excludeTags("android", "ios", "qualification-android", "qualification-ios", "packaged-android")
    }
  }
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
val packagedGrpcProbe = tasks.register<JavaExec>("packagedGrpcProbe") {
  dependsOn(":verity:cli:verifyPackagedGrpc", packagedProbeJar)
  classpath(files(universalJar, packagedProbeJar.flatMap { it.archiveFile }))
  mainClass.set("me.chrisbanes.verity.smoke.PackagedRuntimeProbe")
  maxHeapSize = "512m"
  args("grpc")
}
val selectedVariant = providers.gradleProperty("packagedVariant").orElse("universal")
val packagedReceipts = layout.buildDirectory.dir("reports/packaged")
val packagedAndroidTest = tasks.register<Test>("packagedAndroidTest") {
  dependsOn(":verity:cli:verifyPackagedGrpc", packagedProbeJar)
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  maxHeapSize = "512m"
  useJUnitPlatform { includeTags("packaged-android") }
  inputs.file(universalJar).withPropertyName("selectedProductionJar")
  inputs.file(packagedProbeJar.flatMap { it.archiveFile }).withPropertyName("probeJar")
  inputs.property("packagedVariant", selectedVariant)
  for (name in listOf("VERITY_PACKAGED_ANDROID_SERIAL", "GITHUB_RUN_ID", "GITHUB_SHA")) {
    inputs.property(name, providers.environmentVariable(name).orElse(""))
  }
  val probeArchive = packagedProbeJar.flatMap { it.archiveFile }
  val productionArchive = universalJar
  val variant = selectedVariant
  val receiptDirectory = packagedReceipts
  doFirst {
    check(variant.get() == "universal") {
      "Only the universal prerequisite is implemented before host filtering"
    }
    systemProperty("verity.packaged.jar", productionArchive.get().absolutePath)
    systemProperty("verity.packaged.probe", probeArchive.get().asFile.absolutePath)
    systemProperty("verity.packaged.receipts", receiptDirectory.get().asFile.absolutePath)
  }
}
