plugins {
  id("verity.kotlin-jvm")
  application
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.shadow)
}

application {
  mainClass.set("me.chrisbanes.verity.cli.VerityKt")
}

tasks.shadowJar {
  archiveBaseName.set("verity")
  archiveClassifier.set("")
  isZip64 = true
  mergeServiceFiles()
  // Let the transformers see every service descriptor and Kotlin module metadata file.
  filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
  }
  failOnDuplicateEntries = true
  // The fat JAR exceeds the remote cache upload limit; keep caching compilation and tests.
  outputs.doNotCacheIf("The fat JAR exceeds the remote build cache upload limit") { true }
  // Exclude POM-only artifacts that have no JAR (Shadow cannot expand them as ZIPs)
  dependencies {
    exclude(dependency("org.graalvm.js:js-community"))
  }
}

dependencies {
  implementation(project(":verity:core"))
  implementation(project(":verity:device"))
  implementation(project(":verity:agent"))
  implementation(project(":verity:mcp"))
  implementation(libs.clikt)
  implementation(libs.koog.agents)
  implementation(libs.koog.anthropic)
  implementation(libs.koog.openai)
  implementation(libs.koog.google)
  implementation(libs.koog.openrouter)
  implementation(libs.koog.bedrock)
  implementation(libs.koog.deepseek)
  implementation(libs.koog.mistral)
  implementation(libs.koog.ollama)
  implementation(libs.koog.dashscope)
  implementation(libs.kaml)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(testFixtures(project(":verity:device")))
  testImplementation(testFixtures(project(":verity:agent")))
}
