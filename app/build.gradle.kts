plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xabs.espelho"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xabs.espelho"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
