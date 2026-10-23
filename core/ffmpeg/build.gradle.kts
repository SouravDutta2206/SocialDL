plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.socialdl.src.core.ffmpeg"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// NOTE: Place your statically-linked ffmpeg binaries at:
//   src/main/jniLibs/arm64-v8a/libffmpeg.so
//   src/main/jniLibs/armeabi-v7a/libffmpeg.so
//   src/main/jniLibs/x86_64/libffmpeg.so  (optional, emulator only)

dependencies {
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.junit)
}

