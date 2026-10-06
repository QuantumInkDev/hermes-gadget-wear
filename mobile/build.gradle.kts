import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val signingFile = rootProject.file(".local/signing.properties")
val releaseSigning = Properties().apply {
    if (signingFile.exists()) signingFile.inputStream().use { load(it) }
}

android {
    namespace = "dev.quantumink.hermesgadget.mobile"
    ndkVersion = libs.versions.ndk.get()
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "dev.quantumink.hermesgadget"
        minSdk = 29
        targetSdk = 37
        versionCode = 10000
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (signingFile.exists()) {
        signingConfigs.create("privateRelease") {
            val configured = rootProject.file(
                requireNotNull(releaseSigning.getProperty("storeFile"))
            )
                .canonicalFile
            require(
                configured.toPath().startsWith(rootProject.file(".local").canonicalFile.toPath())
            )
            storeFile = configured
            storePassword = requireNotNull(releaseSigning.getProperty("storePassword"))
            keyAlias = requireNotNull(releaseSigning.getProperty("keyAlias"))
            keyPassword = requireNotNull(releaseSigning.getProperty("keyPassword"))
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("privateRelease")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.play.services.wearable)
    constraints { implementation(libs.fragment) }
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
}
