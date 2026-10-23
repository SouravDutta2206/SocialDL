import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.socialdl.src"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.socialdl.src"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            (this as ExtensionAware).extensions.getByName("python").let { py ->
                py.javaClass.getMethod("srcDir", Any::class.java).invoke(py, File(rootDir, "python/src/main/python"))
            }
        }
    }

    buildTypes {

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}


chaquopy {
    defaultConfig {
        val uvPython = File(System.getProperty("user.home"), "AppData/Roaming/uv/python/cpython-3.11.12-windows-x86_64-none/python.exe")
        if (uvPython.exists()) {
            buildPython(uvPython.absolutePath)
        }
        version = "3.11"
        pip {
            install("yt-dlp==2026.10.21")
            install("yt-dlp-ejs==0.8.0")
            install("instaloader==4.13.1")
            install("requests==2.32.3")
        }
    }
}

dependencies {
    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:settings"))
    implementation(project(":core:python"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:download"))
    implementation(project(":core:ffmpeg"))
    implementation(project(":core:node"))


    // Extraction modules
    implementation(project(":extraction:api"))
    implementation(project(":extraction:youtube"))
    implementation(project(":extraction:instagram"))
    implementation(project(":extraction:twitter"))
    implementation(project(":extraction:threads"))

    // Feature modules
    implementation(project(":feature:home"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:settings"))

    // AndroidX & Material
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.workmanager.runtime.ktx)
    implementation(libs.workmanager.hilt)
    ksp(libs.androidx.hilt.compiler)


    // Testing
    testImplementation(libs.junit)
}