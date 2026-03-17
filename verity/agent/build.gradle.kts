plugins {
  `java-test-fixtures`
}

dependencies {
  implementation(project(":verity:core"))
  implementation(project(":verity:device"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.koog.agents)

  testFixturesImplementation(libs.koog.agents)
}
