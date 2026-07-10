package app.oreshkov.kotlinlibmcp.fetch

import app.oreshkov.kotlinlibmcp.model.KmpTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Gradle Module Metadata (`.module`) DTOs, per spec formatVersion 1.1. Only the subset the
 * fetcher needs; decoded with `ignoreUnknownKeys` so publisher extensions never break parsing.
 * Attribute values may be strings, booleans or numbers, hence [JsonPrimitive].
 */
@Serializable
internal data class GradleModuleMetadata(
    val formatVersion: String,
    val component: GmmComponent? = null,
    val variants: List<GmmVariant> = emptyList(),
)

@Serializable
internal data class GmmComponent(
    val group: String? = null,
    val module: String? = null,
    val version: String? = null,
)

@Serializable
internal data class GmmVariant(
    val name: String,
    val attributes: Map<String, JsonPrimitive> = emptyMap(),
    @SerialName("available-at") val availableAt: GmmAvailableAt? = null,
    val files: List<GmmFile> = emptyList(),
    val dependencies: List<GmmDependency> = emptyList(),
) {
    fun attr(key: String): String? = attributes[key]?.contentOrNull
}

@Serializable
internal data class GmmAvailableAt(
    val url: String? = null,
    val group: String,
    val module: String,
    val version: String,
)

@Serializable
internal data class GmmFile(
    val name: String,
    val url: String,
    val size: Long? = null,
    val sha512: String? = null,
    val sha256: String? = null,
    val sha1: String? = null,
    val md5: String? = null,
)

@Serializable
internal data class GmmDependency(
    val group: String,
    val module: String,
    val version: GmmVersionConstraint? = null,
) {
    val requestedVersion: String?
        get() = version?.let { it.strictly ?: it.requires ?: it.prefers }
}

@Serializable
internal data class GmmVersionConstraint(
    val requires: String? = null,
    val strictly: String? = null,
    val prefers: String? = null,
)

/**
 * A sources variant per the spec is identified by its attributes, never by variant name
 * (`jvmSourcesElements-published` etc. are producer conventions).
 */
internal val GmmVariant.isSourcesVariant: Boolean
    get() = attr("org.gradle.category") == "documentation" && attr("org.gradle.docstype") == "sources"

internal val GmmVariant.isLibraryVariant: Boolean
    get() = attr("org.gradle.category") == "library"

/**
 * KMP target of a variant. Plain JVM libraries carry no
 * `org.jetbrains.kotlin.platform.type` attribute and map to [KmpTarget.UNKNOWN].
 */
internal val GmmVariant.platformType: KmpTarget
    get() = when (attr("org.jetbrains.kotlin.platform.type")) {
        "common" -> KmpTarget.COMMON
        "jvm", "androidJvm" -> KmpTarget.JVM
        "js" -> KmpTarget.JS
        "native" -> KmpTarget.NATIVE
        "wasm" -> KmpTarget.WASM
        else -> KmpTarget.UNKNOWN
    }
