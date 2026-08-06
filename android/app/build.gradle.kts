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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // ComponentActivity = a LifecycleOwner for CameraX; permission APIs.
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")

    // CameraX: preview + frame-by-frame luma analysis feeding the L0 gate.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // WorkManager: in-app model download (resumable, foreground, progress).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Encrypted storage for the Hugging Face access token (gated Gemma downloads).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
}
