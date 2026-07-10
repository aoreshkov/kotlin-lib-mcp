package app.oreshkov.kotlinlibmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A node in the (bounded-depth) dependency tree resolved from a library's `.pom`/`.module`.
 *
 * [scope] is the Maven scope (`compile`, `runtime`, …) when known; [version] inside [coordinate]
 * may be unresolved for BOM-managed deps — those are surfaced best-effort rather than failing the
 * whole tree.
 */
@Serializable
@SerialName("DependencyNode")
public data class DependencyNode(
    val coordinate: LibraryCoordinate,
    val scope: String? = null,
    val target: KmpTarget = KmpTarget.UNKNOWN,
    val children: List<DependencyNode> = emptyList(),
)
