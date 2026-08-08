plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        compose = true
    }
}

dependencies {
    // ComponentActivity = a LifecycleOwner for CameraX; permission APIs.
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")

    // Jetpack Compose (designed UI — versions per AI Edge Gallery known-good set).
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // CameraX: preview + frame-by-frame luma analysis feeding the L0 gate.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Bundled base pose model: single-person, low-latency STREAM_MODE L2 fast path.
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")

    // MediaPipe Tasks Object Detector for the bounded litter-candidate fast path.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    // WorkManager: in-app model download (resumable, foreground, progress).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Encrypted storage for the Hugging Face access token (gated Gemma downloads).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // LiteRT-LM SDK: on-device multimodal Gemma inference for L1 (ADR-0009).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

    // Lottie: machine-eye splash / loading animation (vector, GPU-cheap).
    implementation("com.airbnb.android:lottie-compose:6.6.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
    // Android's org.json API is a stub in host tests; use its compatible JVM implementation.
    testImplementation("org.json:json:20250517")
}
