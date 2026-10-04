plugins {
  id("verity.kotlin-jvm")
  `java-test-fixtures`
}

dependencies {
  implementation(enforcedPlatform(libs.grpc.bom))
  testImplementation(enforcedPlatform(libs.grpc.bom))
  testFixturesImplementation(enforcedPlatform(libs.grpc.bom))
  implementation(project(":verity:core"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  // Android device
  implementation(libs.dadb)
  implementation(libs.maestro.client) {
    exclude(group = "io.grpc", module = "grpc-netty")
  }
  implementation(libs.maestro.orchestra)

  // iOS device
  implementation(libs.maestro.ios.driver)
  implementation(libs.maestro.ios)
  implementation(libs.okhttp)
  implementation(libs.jackson.core)
  implementation(libs.jackson.databind)

  // gRPC with shaded Netty to avoid Ktor conflict
  implementation(libs.grpc.okhttp)
  implementation(libs.grpc.netty.shaded)
  implementation(libs.grpc.stub)
  implementation(libs.grpc.protobuf)

  testFixturesImplementation(project(":verity:core"))
  testFixturesImplementation(libs.maestro.client)
  testFixturesImplementation(libs.kotlinx.coroutines.core)
}

// The fixed offline full-capture proof is bounded to the approved heap ceiling.
tasks.withType<Test>().configureEach {
  maxHeapSize = "512m"
  useJUnitPlatform {
    if (providers.gradleProperty("include.tags").orNull == "ios-bounded-proof") {
      includeTags("ios-bounded-proof")
    } else {
      excludeTags("ios-bounded-proof")
    }
  }
}

configurations.configureEach {
  exclude(group = "io.grpc", module = "grpc-netty")
}
