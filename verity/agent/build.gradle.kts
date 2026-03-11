dependencies {
    implementation(project(":verity:core"))
    implementation(project(":verity:device"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koog.agents)
    implementation(libs.koog.anthropic)
}
