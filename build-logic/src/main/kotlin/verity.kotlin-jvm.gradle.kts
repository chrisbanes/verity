plugins {
  id("org.jetbrains.kotlin.jvm")
  id("verity.spotless")
}

group = "me.chrisbanes.verity"
version = providers.gradleProperty("version")
  .map { it.takeIf { value -> value != "unspecified" } ?: "0.1.0" }
  .getOrElse("0.1.0")

kotlin {
  jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
  testImplementation(kotlin("test"))
  testImplementation(libs.findLibrary("assertk").get())
  testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())
}
