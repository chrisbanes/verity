plugins {
  id("verity.kotlin-jvm")
}

dependencies {
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
  testImplementation(libs.koog.agents)
}

// Maestro 2.11.0 uses gRPC 1.50.2 (AbstractManagedChannelImplBuilder was removed in 1.57).
// Override the main gRPC version from :verity:device to restore Maestro-compatible versions.
val maestroGrpcVersion = "1.50.2"
configurations.all {
  resolutionStrategy {
    force("io.grpc:grpc-netty-shaded:$maestroGrpcVersion")
    force("io.grpc:grpc-stub:$maestroGrpcVersion")
    force("io.grpc:grpc-protobuf:$maestroGrpcVersion")
  }
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
      excludeTags("android", "ios", "qualification-android", "qualification-ios")
    }
  }
}
