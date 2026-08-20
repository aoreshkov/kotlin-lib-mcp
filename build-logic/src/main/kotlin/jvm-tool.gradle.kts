/*
 * Convention for repo tooling that is built but never shipped: no logging backend, no
 * `application` wiring, no stdin forwarding — modules applying this declare their own
 * `JavaExec` tasks. Kept separate from `jvm-application` so a tool never inherits the
 * server's runtime concerns.
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
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
