plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.musicremover.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.musicremover.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "3.0.0"

        buildConfigField("String", "API_BASE_URL", "\"http://localhost:8000\"")

        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxHeapSize = "1g"
            it.systemProperty("murem.parityDir", (project.findProperty("murem.parityDir") ?: "").toString())
        }
    }
}

// The Android ORT AAR loads natives via System.loadLibrary; JVM unit tests use the desktop artifact instead.
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Video player
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // On-device fallback: re-encode a video track MediaMuxer cannot copy to H.264
    implementation("androidx.media3:media3-transformer:1.5.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // On-device separation
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")

    // On-device links: yt-dlp (Python) and ffmpeg runtimes
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.1")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
