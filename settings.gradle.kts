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