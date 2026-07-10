package app.oreshkov.kotlinlibmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Summary of a single package discovered in a library's sources. */
@Serializable
@SerialName("PackageInfo")
public data class PackageInfo(
    val name: String,
    val declarationCount: Int,
    val targets: List<KmpTarget> = emptyList(),
)
