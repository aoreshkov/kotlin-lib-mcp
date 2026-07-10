import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // Bundled in the Kotlin Gradle plugin (already on the build-logic classpath), so it applies
    // version-less like the multiplatform plugin — avoids loading Kotlin twice with an explicit
    // version at the subproject level. Every kmp-library module returns `@Serializable` DTOs.
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

// `libs` type-safe accessor is unavailable in precompiled script plugins on Gradle 9;
// resolve the catalog at configuration time instead.
val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvm()
    jvmToolchain(21)

    // Published-library policy: every public declaration must carry an explicit visibility and
    // return type. Pairs with the binary-compatibility-validator applied above so the ABI is
    // intentional, not inferred.
    explicitApi()

    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(libs.findLibrary("kotlin-test").get())
            }
        }
    }
}
