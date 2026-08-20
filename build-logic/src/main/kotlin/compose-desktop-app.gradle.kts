plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)

    // The codebase compiles warning-free; keep it that way. A Kotlin bump that introduces a new
    // deprecation will fail the build here, which is the point — that is exactly when it is
    // cheapest to act on, and this project bumps Kotlin deliberately rather than incidentally.
    compilerOptions {
        allWarningsAsErrors = true
    }
}

compose.desktop {
    application {
        mainClass = "AppKt"
    }
}
