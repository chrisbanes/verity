plugins {
  application
}

application {
  mainClass.set("me.chrisbanes.verity.cli.VerityKt")
}

dependencies {
  implementation(project(":verity:core"))
  implementation(project(":verity:device"))
  implementation(project(":verity:agent"))
  implementation(project(":verity:mcp"))
  implementation(libs.clikt)
  implementation(libs.koog.agents)
  implementation(libs.koog.anthropic)
  implementation(libs.kotlinx.coroutines.core)
}
