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
    namespace = "dev.quantumink.hermesgadget.wear"
    ndkVersion = libs.versions.ndk.get()
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "dev.quantumink.hermesgadget"
        minSdk = 33
        targetSdk = 37
        versionCode = 10001
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
    implementation(libs.wear.material3)
    implementation(libs.activity.compose)
    implementation(libs.wear.foundation)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.play.services.wearable)
    implementation(libs.concurrent.futures)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.complications)
    constraints { implementation(libs.fragment) }
    testImplementation(libs.junit)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.okhttp.tls)
}
