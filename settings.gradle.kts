pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://chaquo.com/maven-test") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://chaquo.com/maven-test") }
    }
}

rootProject.name = "SocialDL"

// ── App ──
include(":app")

// ── Core ──
include(":core:common")
include(":core:model")
include(":core:ui")
include(":core:network")
include(":core:database")
include(":core:settings")
include(":core:python")
include(":core:download")
include(":core:ffmpeg")
include(":core:node")


// ── Extraction ──
include(":extraction:api")
include(":extraction:youtube")
include(":extraction:instagram")
include(":extraction:twitter")
include(":extraction:threads")

// ── Feature ──
include(":feature:home")
include(":feature:downloads")
include(":feature:settings")
