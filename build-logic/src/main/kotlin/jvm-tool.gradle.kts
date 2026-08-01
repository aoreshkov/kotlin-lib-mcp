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
}
