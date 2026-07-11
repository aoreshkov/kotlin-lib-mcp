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