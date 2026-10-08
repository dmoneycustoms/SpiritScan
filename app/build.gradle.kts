plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nscb.spiritscan"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nscb.spiritscan"
        minSdk = 26
        targetSdk = 35
        versionCode = 91
        versionName = "8.9.0-context-novelty"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Known-good. CameraX 1.6.x / 1.7.0-alpha pull AndroidX libs that require AGP 8.9.1+
// (and a newer compileSdk). To go bleeding-edge: bump AGP in build.gradle.kts to 8.9.1+,
// raise compileSdk, then set this to "1.7.0-alpha03" (or "1.6.1" for stable).
val cameraxVersion = "1.3.4"

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    // ML Kit object detection (on-device)
    implementation("com.google.mlkit:object-detection:17.0.1")
    // MaRS OOD ONNX
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
    // TFLite runner is a stub until vision_ood.tflite + Interpreter are wired
    debugImplementation("androidx.compose.ui:ui-tooling")
}
