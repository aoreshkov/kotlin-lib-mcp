package app.oreshkov.kotlinlibmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kotlin Multiplatform target a source file or declaration belongs to.
 *
 * KMP libraries publish sources per target ([COMMON] + one or more platforms), so symbols are
 * tagged with the variant they came from. [UNKNOWN] is the safe fallback when a publisher uses
 * an attribute we don't recognise — keeping it on the enum lets old cached indexes deserialize
 * unchanged as the model grows.
 */
@Serializable
public enum class KmpTarget {
    @SerialName("common")
    COMMON,

    @SerialName("jvm")
    JVM,

    @SerialName("js")
    JS,

    @SerialName("native")
    NATIVE,

    @SerialName("wasm")
    WASM,

    @SerialName("unknown")
    UNKNOWN,
}
