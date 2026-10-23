import java.io.File

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.socialdl.src.core.python"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
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

    compileOptions {

        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

chaquopy {
    defaultConfig {
        val uvPython = File(System.getProperty("user.home"), "AppData/Roaming/uv/python/cpython-3.11.12-windows-x86_64-none/python.exe")
        if (uvPython.exists()) {
            buildPython(uvPython.absolutePath)
        }
        version = "3.11"
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Testing
    testImplementation(libs.junit)
}
