import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider

plugins {
    id("kmp-library")
}

// The KMP `implementation(notation) { … }` overload takes a String, not a catalog Provider,
// so expose the "group:name:version" coordinate to configure transitivity per dependency.
fun Provider<MinimalExternalModuleDependency>.coordinates(): String =
    get().let { "${it.module.group}:${it.module.name}:${it.version}" }

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                // Annotations (`@Serializable`/`@SerialName`) + `KSerializer` live in `-core`.
                // The `-json` encoder is only pulled in where we actually serialize (JVM phases).
                implementation(libs.kotlinx.serialization.core)
            }
        }
        getByName("jvmMain") {
            dependencies {
                // Maven source acquisition (Phase 03): Ktor client for repo access, `-json`
                // for `.module` metadata + cached FetchResult, Kermit for stderr-safe logging.
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kermit)
                // Kotlin Analysis API (standalone, K2/FIR) — JVM-only, version-matched to Kotlin.
                // The `-for-ide` shaded jars declare logical (non-`-for-ide`) deps that aren't
                // published separately, so each is pulled with transitive resolution disabled.
                implementation(libs.analysis.api.standalone.coordinates()) { isTransitive = false }
                implementation(libs.analysis.api.high.level.coordinates()) { isTransitive = false }
                implementation(libs.analysis.api.k2.coordinates()) { isTransitive = false }
                implementation(libs.analysis.api.low.level.fir.coordinates()) { isTransitive = false }
                implementation(libs.analysis.api.impl.base.coordinates()) { isTransitive = false }
                implementation(libs.analysis.api.platform.coordinates()) { isTransitive = false }
                implementation(libs.analysis.api.symbol.light.classes.coordinates()) { isTransitive = false }
                // Compiler + bundled IntelliJ core, resolved transitively from Maven Central.
                implementation(libs.kotlin.compiler)
                // Runtime-only deps of the non-transitive `-for-ide` jars (LL FIR caches), plus
                // the JetBrains coroutines fork the bundled IJ core's thread pool expects
                // (KT-81457 workaround, same as detekt) — without it pooled workers die with
                // NoClassDefFoundError: kotlinx.coroutines.internal.intellij.IntellijCoroutines.
                runtimeOnly(libs.caffeine)
                runtimeOnly(libs.kotlinx.coroutines.core.intellij)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}
