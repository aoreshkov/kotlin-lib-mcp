package app.oreshkov.kotlinlibmcp.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parsed, cacheable index of a library's API surface that MCP tools read.
 *
 * Built once per coordinate (Phase 04) and persisted as JSON in the cache, so tool calls answer
 * without re-running source analysis. [symbolsByFqName] is the fast lookup tools key into;
 * [packages] and [files] back the listing/search tools.
 *
 * [fetchedAt] uses the stdlib [Instant] (serialized via kotlinx-serialization's built-in
 * serializer) — no `kotlinx-datetime` dependency.
 */
@Serializable
@SerialName("LibraryIndex")
public data class LibraryIndex(
    val coordinate: LibraryCoordinate,
    val targets: List<KmpTarget> = emptyList(),
    val packages: List<PackageInfo> = emptyList(),
    val symbolsByFqName: Map<String, ApiSymbol> = emptyMap(),
    val files: List<SourceFileRef> = emptyList(),
    val fetchedAt: Instant,
)
