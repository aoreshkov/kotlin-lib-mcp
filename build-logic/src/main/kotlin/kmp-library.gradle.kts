import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // Its artifact (`org.jetbrains.kotlin:kotlin-serialization`) is on the build-logic classpath,
    // so it applies version-less like the multiplatform plugin — avoids loading Kotlin twice with
    // an explicit version at the subproject level. Every kmp-library module returns
    // `@Serializable` DTOs.
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
}

// `libs` type-safe accessor is unavailable in precompiled script plugins on Gradle 9;
// resolve the catalog at configuration time instead.
val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvm()
    jvmToolchain(21)

    // Published-library policy: every public declaration must carry an explicit visibility and
    // return type. Pairs with the ABI validation below so the ABI is intentional, not inferred.
    explicitApi()

    // ABI validation is built into the Kotlin Gradle plugin; the standalone
    // `binary-compatibility-validator` plugin it replaces is frozen (maintenance only).
    // The DSL is still experimental — see kotlin-gradle-plugin-api's ExperimentalAbiValidation.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Keep the dump where the old plugin wrote it, so `core/api/core.api` stays under review.
        referenceDumpDir.set(layout.projectDirectory.dir("api"))
    }

    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(libs.findLibrary("kotlin-test").get())
            }
        }
    }
}
