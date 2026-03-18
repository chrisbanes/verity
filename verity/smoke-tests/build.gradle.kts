dependencies {
  testImplementation(project(":verity:core"))
  testImplementation(project(":verity:device"))
  testImplementation(project(":verity:agent"))
  testImplementation(testFixtures(project(":verity:agent")))
  testImplementation(libs.kotlinx.serialization.json)
  testImplementation(libs.dadb)
  testImplementation(libs.koog.agents)
}

// Maestro 2.3.0 requires gRPC <=1.56 (uses AbstractManagedChannelImplBuilder removed in 1.57).
// Override the forced 1.79.0 from :verity:device to restore Maestro-compatible versions.
val maestroGrpcVersion = "1.50.2"
configurations.all {
  resolutionStrategy {
    force("io.grpc:grpc-netty-shaded:$maestroGrpcVersion")
    force("io.grpc:grpc-stub:$maestroGrpcVersion")
    force("io.grpc:grpc-protobuf:$maestroGrpcVersion")
    force("io.grpc:grpc-protobuf-lite:$maestroGrpcVersion")
    force("io.grpc:grpc-core:$maestroGrpcVersion")
    force("io.grpc:grpc-api:$maestroGrpcVersion")
    force("io.grpc:grpc-context:$maestroGrpcVersion")
  }
}

tasks.test {
  // Excluded from ./gradlew check by default.
  // Run explicitly: ./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=android
  //            or:  ./gradlew :verity:smoke-tests:smokeTest -Dinclude.tags=ios
  enabled = false
}

tasks.register<Test>("smokeTest") {
  description = "Run device smoke tests (requires a running emulator or simulator)"
  group = "verification"

  useJUnitPlatform {
    val tags = System.getProperty("include.tags")
    if (tags != null) {
      includeTags(tags)
    }
  }

  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
}
