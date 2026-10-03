plugins {
  id("verity.kotlin-jvm")
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kaml)
  implementation(libs.kotlinx.coroutines.core)
}

tasks.register<JavaExec>("assertionPlanningResearch") {
  group = "verification"
  description = "Run the offline Issue 59 assertion-planning research host."
  dependsOn(tasks.named("testClasses"))
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("me.chrisbanes.verity.core.research.AssertionPlanningResearchKt")
  workingDir = rootProject.projectDir
}
