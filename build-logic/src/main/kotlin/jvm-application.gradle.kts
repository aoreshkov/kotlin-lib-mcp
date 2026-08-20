import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    // So `:server` is measured too. Kover used to be applied only by `kmp-library`, which left
    // the largest module in the build with no coverage at all. Merged by the root build's
    // `coverage-report` convention.
    id("org.jetbrains.kotlinx.kover")
}

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)

    // The codebase compiles warning-free; keep it that way. A Kotlin bump that introduces a new
    // deprecation will fail the build here, which is the point — that is exactly when it is
    // cheapest to act on, and this project bumps Kotlin deliberately rather than incidentally.
    compilerOptions {
        allWarningsAsErrors = true
    }
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

// See the matching block in `kmp-library` for why the floor is per-module. `:server` reaches 65%
// lines today — lower than `core` because the transports, `Main`'s argument parsing and the
// telemetry wiring are exercised end-to-end rather than by unit tests.
kover {
    reports {
        verify {
            rule("Line coverage of the application must not regress") {
                bound {
                    minValue = 60
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
