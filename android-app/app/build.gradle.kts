plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tyerbbased.gyrowheel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tyerbbased.gyrowheel"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.0-alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

