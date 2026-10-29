plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.socialdl.src.core.download"
    compileSdk = 35

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Robolectric spins up a real Android runtime (with a working SQLite)
            // for the Phase 3 enqueue-coalescing tests around Room.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:ffmpeg"))
    implementation(project(":core:network"))
    implementation(project(":core:settings"))
    implementation(project(":extraction:api"))

    implementation(libs.workmanager.runtime.ktx)
    implementation(libs.workmanager.hilt)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Room KTX (withTransaction) — :core:database keeps room-ktx `implementation`,
    // so dependents need it explicitly for the RoomDatabase.withTransaction extension.
    implementation(libs.room.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    ksp(libs.androidx.hilt.compiler)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    // Robolectric + AndroidX test core: in-memory Room instance and a real
    // Android Context for the enqueue-coalescing / concurrency tests (Phase 3).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // On-device Phase 3 test: real SQLite + real WorkManager scheduler.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

