dependencies {
  testImplementation(project(":verity:core"))
  testImplementation(project(":verity:device"))
  testImplementation(project(":verity:agent"))
  testImplementation(testFixtures(project(":verity:agent")))
  testImplementation(libs.kotlinx.serialization.json)
  testImplementation(libs.dadb)
}

tasks.test {
  // Excluded from ./gradlew check by default.
  // Run explicitly: ./gradlew :verity:smoke-tests:test -Dinclude.tags=android
  //            or:  ./gradlew :verity:smoke-tests:test -Dinclude.tags=ios
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
