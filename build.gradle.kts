import org.gradle.api.attributes.Bundling
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

allprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.allWarningsAsErrors.set(true)
    }
}

val ktlint by configurations.creating {
    attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
}

dependencies {
    ktlint(libs.ktlint)
}

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Check Kotlin source and Gradle formatting."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("**/*.kt", "**/*.kts", "!**/build/**", "!**/.local/**", "!**/.gradle/**")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Format Kotlin source and Gradle scripts."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("--format", "**/*.kt", "**/*.kts", "!**/build/**", "!**/.local/**", "!**/.gradle/**")
}

tasks.register("check") {
    group = "verification"
    dependsOn(
        "ktlintCheck",
        ":protocol:check",
        ":wear:lintDebug",
        ":mobile:lintDebug",
        ":wear:testDebugUnitTest",
        ":mobile:testDebugUnitTest"
    )
}
