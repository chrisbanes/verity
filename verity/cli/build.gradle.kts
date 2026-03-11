plugins {
  application
}

application {
  mainClass.set("me.chrisbanes.verity.cli.VerityKt")
}

dependencies {
  implementation(project(":verity:agent"))
  implementation(project(":verity:mcp"))
  implementation(libs.clikt)
}
