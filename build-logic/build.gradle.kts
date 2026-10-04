plugins {
  `kotlin-dsl`
}

dependencies {
  testImplementation(kotlin("test"))
  testImplementation("com.willowtreeapps.assertk:assertk:0.28.1")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
  implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

// Explicit offline archive sensitivity suite; ordinary unit tests need no assembled CLI.
tasks.test { exclude("**/HostPackagingArchiveTest.class") }
tasks.register<Test>("hostPackagingArchiveTest") {
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  include("**/HostPackagingArchiveTest.class")
  val universal = providers.gradleProperty("hostPackagingUniversalArchive")
  val macos = providers.gradleProperty("hostPackagingFixtureArchive")
  inputs.files(universal, macos).withPropertyName("packagedArchives")
  if (universal.isPresent && macos.isPresent) {
    systemProperty("hostPackaging.universal", universal.get())
    systemProperty("hostPackaging.macos", macos.get())
  }
}
