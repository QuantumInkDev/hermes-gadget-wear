plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
}

tasks.test {
    workingDir = rootProject.projectDir
    inputs.property(
        "sdkDevserverEnabled",
        providers.environmentVariable("HERMES_GADGET_PYTHON").map { it.isNotBlank() }.orElse(false)
    )
}
