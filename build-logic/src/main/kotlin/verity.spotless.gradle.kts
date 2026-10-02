import com.diffplug.spotless.LineEnding

plugins {
  id("com.diffplug.spotless")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

spotless {
  // Avoid Git-based line-ending discovery during configuration.
  lineEndings = LineEnding.UNIX

  kotlin {
    target("src/*/kotlin/**/*.kt", "src/*/java/**/*.kt")
    ktlint(libs.findVersion("ktlint").get().requiredVersion)
  }
  kotlinGradle {
    if (project.path == ":") {
      target("*.gradle.kts", "build-logic/**/*.gradle.kts")
      targetExclude("build-logic/build/**", "build-logic/.gradle/**")
    } else {
      target("*.gradle.kts")
    }
    ktlint(libs.findVersion("ktlint").get().requiredVersion)
  }
}
