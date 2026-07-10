import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
}

dependencies {
    "runtimeOnly"(libs.findLibrary("logback-classic").get())
}

application {
    mainClass.set("MainKt")
}

// Forward the caller's stdin so `gradlew :server:run` can speak MCP over stdio.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
