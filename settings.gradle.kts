rootProject.name = "Kotlin Lib MCP"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Gradle's toolchain auto-provisioning: downloads the JDK the convention plugins ask for (`jdk`
    // in libs.versions.toml) when no matching one is installed. A settings plugin is applied before
    // the version catalog exists, so its version is the one that cannot live in the catalog.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // IntelliJ repositories — only needed for the Kotlin Analysis API standalone artifacts.
        // Content-filtered so they can never shadow Maven Central artifacts.
        maven("https://www.jetbrains.com/intellij-repository/releases") {
            mavenContent { includeGroupAndSubgroups("org.jetbrains") }
        }
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies") {
            mavenContent { includeGroupAndSubgroups("org.jetbrains") }
        }
    }
}

include(":core", ":server", ":dashboard")

// The SEP-973 icon generator. The module itself never ships — its *output* does: the PNGs under
// server/src/main/resources/icons/ ride inline in every tools/list response. Built by
// `./gradlew build` so it cannot rot, but nothing depends on it.
include(":tools")