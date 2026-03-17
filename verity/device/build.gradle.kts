plugins {
  `java-test-fixtures`
}

dependencies {
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

  // gRPC with shaded Netty to avoid Ktor conflict
  implementation(libs.grpc.netty.shaded)
  implementation(libs.grpc.stub)
  implementation(libs.grpc.protobuf)

  testFixturesImplementation(project(":verity:core"))
}

val grpcVersion: String = rootProject.extensions
  .getByType<VersionCatalogsExtension>()
  .named("libs")
  .findVersion("grpc")
  .get()
  .requiredVersion

configurations.all {
  resolutionStrategy {
    force("io.grpc:grpc-stub:$grpcVersion")
    force("io.grpc:grpc-protobuf:$grpcVersion")
    force("io.grpc:grpc-core:$grpcVersion")
    force("io.grpc:grpc-api:$grpcVersion")
    force("io.grpc:grpc-context:$grpcVersion")
  }
}
