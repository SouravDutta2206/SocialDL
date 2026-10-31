plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release builds must pin the helper signing certificate SHA-256. Comma-separate
// multiple values during key rotation. Debug builds may leave this empty for
// local adb-installed helper testing; release builds reject an empty pin.
val helperCertSha256 = providers.gradleProperty("socialdl.helperCertSha256")
    .orElse("")
    .get()

android {
    namespace = "com.socialdl.src.core.runtime"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "HELPER_CERT_SHA256", "\"$helperCertSha256\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:common"))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.junit)
}
