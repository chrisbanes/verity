plugins {
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kaml)
  implementation(libs.kotlinx.coroutines.core)
}
