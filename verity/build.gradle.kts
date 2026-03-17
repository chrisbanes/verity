subprojects {
  apply(plugin = "org.jetbrains.kotlin.jvm")

  group = "me.chrisbanes.verity"
  version = findProperty("version")?.toString()?.takeIf { it != "unspecified" } ?: "0.1.0"

  extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    jvmToolchain(21)
  }

  tasks.withType<Test> {
    useJUnitPlatform()
  }

  dependencies {
    "testImplementation"(kotlin("test"))
    "testImplementation"(rootProject.libs.assertk)
    "testImplementation"(rootProject.libs.kotlinx.coroutines.test)
  }
}
