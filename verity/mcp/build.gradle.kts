dependencies {
  implementation(project(":verity:core"))
  implementation(project(":verity:device"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
}
