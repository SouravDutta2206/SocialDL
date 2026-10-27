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
            // Keep Chaquopy limited to the ABI that has real Node/FFmpeg binaries.
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            // Chaquopy 15.0.1 has no type-safe Kotlin-DSL accessor for the per-source-set
            // "python" extension, so we resolve it reflectively. This is now the ONLY
            // reflective call in the build — do not copy it to other modules.
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
        // Machine-specific interpreter path: -P property wins, then SOCIALDL_PYTHON.
        // Unset or missing file → Chaquopy falls back to `python` on PATH (CI).
        val configuredPython = providers.gradleProperty("socialdl.python.buildPython")
            .orElse(providers.environmentVariable("SOCIALDL_PYTHON"))
            .orNull
            ?.trim()
        if (!configuredPython.isNullOrBlank() && File(configuredPython).exists()) {
            buildPython(configuredPython)
        } else {
            logger.lifecycle("socialdl.python.buildPython not set; Chaquopy will use Python from PATH.")
        }
        version = "3.11"

        // Single source of truth for pip deps (R-E.3): the bridge module owns the pins.
        pip {
            File(rootDir, "python/requirements.txt")
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .forEach { install(it) }
        }
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
