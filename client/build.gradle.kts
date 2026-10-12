plugins { alias(libs.plugins.android.library) }

android {
    namespace = "dev.quantumink.hermesgadget.client"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { minSdk = 29 }
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
    implementation(libs.play.services.wearable)
    constraints { implementation(libs.fragment) }
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
