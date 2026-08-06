plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.claustrum"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.claustrum"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Only ship the arm64 core for now (matches cargo-ndk -t arm64-v8a).
        ndk { abiFilters += "arm64-v8a" }
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

    // Prebuilt libclaustrum_core.so lives here (produced by cargo-ndk).
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}
