package app.oreshkov.kotlinlibmcp.dto

import app.oreshkov.kotlinlibmcp.model.ApiSymbol
import app.oreshkov.kotlinlibmcp.model.DependencyNode
import app.oreshkov.kotlinlibmcp.model.KDoc
import app.oreshkov.kotlinlibmcp.model.KmpTarget
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.PackageInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Response shapes returned by the MCP tools, shared with the `server` module so tools just
 * serialize a value object instead of hand-rolling JSON. All read-only collections with defaults
 * so the wire/cache format stays forward-compatible as fields are added.
 */

/** `fetch_library` — summary of a warmed coordinate. */
@Serializable
@SerialName("FetchSummary")
public data class FetchSummary(
    val coordinate: LibraryCoordinate,
    val resolvedTargets: List<KmpTarget> = emptyList(),
    val sourceFileCount: Int = 0,
    val packageCount: Int = 0,
    val fromCache: Boolean = false,
)

/** `list_packages`. */
@Serializable
@SerialName("PackageList")
public data class PackageList(
    val coordinate: LibraryCoordinate,
    val packages: List<PackageInfo> = emptyList(),
)

/**
 * `list_declarations`. [declarations] is a bounded page; [totalCount] is how many matched the
 * filter before paging, and [truncated] is `true` when more matched than the returned page (advance
 * `offset` to fetch the rest).
 */
@Serializable
@SerialName("DeclarationList")
public data class DeclarationList(
    val coordinate: LibraryCoordinate,
    val packageName: String? = null,
    val declarations: List<ApiSymbol> = emptyList(),
    val totalCount: Int = 0,
    val truncated: Boolean = false,
)

/** `get_api_signature`. */
@Serializable
@SerialName("SignatureResult")
public data class SignatureResult(
    val symbol: ApiSymbol,
)

/** `get_kdoc`. */
@Serializable
@SerialName("KDocResult")
public data class KDocResult(
    val fqName: String,
    val kdoc: KDoc? = null,
)

/** `get_source` — raw source of a file or a single declaration. */
@Serializable
@SerialName("SourceResult")
public data class SourceResult(
    val path: String,
    val content: String,
    val startLine: Int = 1,
)

/** One hit from `search_source`. */
@Serializable
@SerialName("SearchHit")
public data class SearchHit(
    val path: String,
    val line: Int,
    val snippet: String,
)

/** `search_source`. [truncated] is `true` when more hits existed than the bounded result cap. */
@Serializable
@SerialName("SearchResults")
public data class SearchResults(
    val query: String,
    val hits: List<SearchHit> = emptyList(),
    val truncated: Boolean = false,
)

/** `get_dependencies`. */
@Serializable
@SerialName("DependencyResult")
public data class DependencyResult(
    val root: DependencyNode,
)

/** `list_versions`. */
@Serializable
@SerialName("VersionList")
public data class VersionList(
    val group: String,
    val artifact: String,
    val versions: List<String> = emptyList(),
)

/**
 * `get_latest_version`. [latestStable] is the newest non-pre-release (the repository `<release>`
 * tag, or the semantically-highest stable version); [latest] is the newest overall including
 * pre-releases. Either may be `null` (e.g. no stable release published yet).
 */
@Serializable
@SerialName("LatestVersion")
public data class LatestVersion(
    val group: String,
    val artifact: String,
    val latestStable: String? = null,
    val latest: String? = null,
    val includedPreReleases: Boolean = false,
    val totalVersions: Int = 0,
)
