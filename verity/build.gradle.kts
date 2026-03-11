subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "me.chrisbanes.verity"
    version = "0.1.0"

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
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
