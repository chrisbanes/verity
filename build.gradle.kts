plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.spotless) apply false
}

allprojects {
  apply(plugin = "com.diffplug.spotless")
  pluginManager.withPlugin("com.diffplug.spotless") {
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
      kotlin {
        target("src/*/kotlin/**/*.kt", "src/*/java/**/*.kt")
        ktlint(libs.versions.ktlint.get())
      }

      kotlinGradle {
        target("**/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
      }
    }
  }
}
