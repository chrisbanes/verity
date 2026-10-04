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
