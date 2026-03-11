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
