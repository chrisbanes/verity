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
  }
}

tasks.test {
  // Excluded from ./gradlew check by default.
  // Run explicitly: ./gradlew :verity:smoke-tests:test -Pinclude.tags=android
  //            or:  ./gradlew :verity:smoke-tests:test -Pinclude.tags=ios
  val includeTags = providers.gradleProperty("include.tags")
  useJUnitPlatform {
    val tags = includeTags.orNull
    if (tags != null) {
      includeTags(tags)
    }
  }
  onlyIf { includeTags.isPresent }
}
