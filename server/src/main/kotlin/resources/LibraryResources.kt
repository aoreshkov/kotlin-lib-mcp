package app.oreshkov.kotlinlibmcp.server.resources

import app.oreshkov.kotlinlibmcp.model.KmpTarget
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.PackageInfo
import app.oreshkov.kotlinlibmcp.model.SymbolKind
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import app.oreshkov.kotlinlibmcp.server.telemetry.resourceSpan
import app.oreshkov.kotlinlibmcp.server.tools.toolJson
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredResource
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** Stable, parseable URI for a cached library's index — the dashboard and clients rely on it. */
fun libraryIndexUri(coordinate: LibraryCoordinate): String =
    "kotlinlib://${coordinate.group}/${coordinate.artifact}/${coordinate.version}/index"

/** URI of one package's public API within a cached library; see [LIBRARY_PACKAGE_URI_TEMPLATE]. */
fun libraryPackageUri(coordinate: LibraryCoordinate, packageName: String): String =
    "kotlinlib://${coordinate.group}/${coordinate.artifact}/${coordinate.version}/package/$packageName"

/** URI template matching every library index URI; see [libraryIndexUri]. */
const val LIBRARY_INDEX_URI_TEMPLATE: String = "kotlinlib://{group}/{artifact}/{version}/index"

/** URI template matching every package URI; see [libraryPackageUri]. */
const val LIBRARY_PACKAGE_URI_TEMPLATE: String = "kotlinlib://{group}/{artifact}/{version}/package/{package}"

/*
 * Both resources are bounded, for the reason every tool result is (`.claude/rules/mcp-server.md`):
 * their size would otherwise be a function of the library. A resource has no arguments to page with,
 * and a client may attach one to a conversation wholesale — the full index, every declaration with
 * its KDoc, runs to megabytes for a large library. So the index is a summary that leads to per-package
 * resources, and each of those is capped too; the tools page through the rest.
 */

/** Packages an index summary lists. A package entry is ~90 bytes, so this caps it near 18 KB. */
private const val MAX_INDEX_PACKAGES = 200

/** Declarations a package resource lists: signature plus KDoc summary, a few hundred bytes each. */
private const val MAX_PACKAGE_DECLARATIONS = 200

/**
 * What `…/index` returns: a bounded summary of a cached library. [packages] holds the first
 * [MAX_INDEX_PACKAGES] by name; each one's public API is readable at [packageUriTemplate].
 */
@Serializable
internal data class LibraryIndexSummary(
    val coordinate: LibraryCoordinate,
    val targets: List<KmpTarget>,
    val fetchedAt: Instant,
    val declarationCount: Int,
    val fileCount: Int,
    val packageCount: Int,
    val packages: List<PackageInfo>,
    val packagesTruncated: Boolean,
    /** Where each package's public API can be read; substitute `{package}`. */
    val packageUriTemplate: String,
)

/** What `…/package/{package}` returns: the package's public declarations, capped. */
@Serializable
internal data class PackageApi(
    val coordinate: LibraryCoordinate,
    val packageName: String,
    val declarations: List<DeclarationDigest>,
    val totalCount: Int,
    val truncated: Boolean,
)

/** One declaration as a package resource shows it: the signature and the KDoc's first sentence. */
@Serializable
internal data class DeclarationDigest(
    val fqName: String,
    val kind: SymbolKind,
    val signature: String,
    val summary: String? = null,
)

/**
 * Registers the `kotlinlib://{group}/{artifact}/{version}/index` resource template so clients can
 * address any cached library's summary directly, without first discovering it via `resources/list`.
 * Exact-URI resources registered by [LibraryIndexResources] take priority; the template answers
 * reads for cached coordinates that (for whatever reason) lack a static registration and gives
 * uncached coordinates a "call fetch_library first" error instead of a bare resource-not-found.
 */
fun Server.registerLibraryIndexTemplate(service: LibraryService) {
    addResourceTemplate(
        ResourceTemplate(
            uriTemplate = LIBRARY_INDEX_URI_TEMPLATE,
            name = "Library API index",
            description = INDEX_DESCRIPTION + " The coordinate must have been fetched with fetch_library first.",
            mimeType = "application/json",
            icons = Glyph.Index.icons,
        )
    ) { request, variables ->
        resourceSpan(request.uri, sessionId, request.params.meta) {
            readIndexSummary(service, variables.coordinate(), request.uri)
        }
    }
}

/**
 * Registers the `kotlinlib://{group}/{artifact}/{version}/package/{package}` resource template: one
 * package's public declarations, which an index summary points to. Template-only — a static resource
 * per package would put thousands of entries in `resources/list` — and completable, so a client can
 * pick the package from the cache (`completions/LibraryCompletions.kt`).
 */
fun Server.registerLibraryPackageTemplate(service: LibraryService) {
    addResourceTemplate(
        ResourceTemplate(
            uriTemplate = LIBRARY_PACKAGE_URI_TEMPLATE,
            name = "Library package API",
            description = "Public declarations of one package of a fetched library — signature and KDoc " +
                "summary each, up to $MAX_PACKAGE_DECLARATIONS ('truncated: true' when there are more; " +
                "list_declarations pages through the rest). The coordinate must have been fetched with " +
                "fetch_library first.",
            mimeType = "application/json",
            icons = Glyph.Declarations.icons,
        )
    ) { request, variables ->
        resourceSpan(request.uri, sessionId, request.params.meta) {
            val coordinate = variables.coordinate()
            // Not a path: only ever compared against the index's package names, which is also what
            // turns a misspelt package into an error rather than an empty page.
            val packageName = variables.getValue("package")
            val page = service.listDeclarations(
                coordinate,
                packageName,
                visibility = "public",
                maxResults = MAX_PACKAGE_DECLARATIONS,
            )
            jsonContents(
                request.uri,
                PackageApi(
                    coordinate = coordinate,
                    packageName = packageName,
                    declarations = page.declarations.map { symbol ->
                        DeclarationDigest(symbol.fqName, symbol.kind, symbol.signature, symbol.kdoc?.summary)
                    },
                    totalCount = page.totalCount,
                    truncated = page.truncated,
                ),
            )
        }
    }
}

private const val INDEX_DESCRIPTION: String =
    "Summary of a fetched library: KMP targets, declaration/file/package counts, and its packages " +
        "(up to $MAX_INDEX_PACKAGES), each of whose public API is readable at " +
        "$LIBRARY_PACKAGE_URI_TEMPLATE."

private suspend fun readIndexSummary(
    service: LibraryService,
    coordinate: LibraryCoordinate,
    uri: String,
): ReadResourceResult {
    val index = service.index(coordinate)
    val packages = index.packages.sortedBy { it.name }
    return jsonContents(
        uri,
        LibraryIndexSummary(
            coordinate = coordinate,
            targets = index.targets,
            fetchedAt = index.fetchedAt,
            declarationCount = index.symbolsByFqName.size,
            fileCount = index.files.size,
            packageCount = packages.size,
            packages = packages.take(MAX_INDEX_PACKAGES),
            packagesTruncated = packages.size > MAX_INDEX_PACKAGES,
            packageUriTemplate = libraryPackageUri(coordinate, "{package}"),
        ),
    )
}

private inline fun <reified T> jsonContents(uri: String, value: T): ReadResourceResult = ReadResourceResult(
    contents = listOf(
        TextResourceContents(text = toolJson.encodeToString(value), uri = uri, mimeType = "application/json"),
    ),
)

/** Maven coordinate segments: dots, dashes, plus — but never path separators or dot-segments. */
private val COORDINATE_SEGMENT = Regex("""[A-Za-z0-9_+-][A-Za-z0-9._+-]*""")

/** Template variables are attacker-controlled URI segments and end up in cache paths. */
private fun Map<String, String>.coordinate(): LibraryCoordinate = LibraryCoordinate(
    group = coordinateSegment("group"),
    artifact = coordinateSegment("artifact"),
    version = coordinateSegment("version"),
)

private fun Map<String, String>.coordinateSegment(name: String): String {
    val value = getValue(name)
    require(COORDINATE_SEGMENT.matches(value)) { "Invalid $name segment in resource URI: '$value'" }
    return value
}

/**
 * Exposes one MCP resource per cached library: reading
 * `kotlinlib://{group}/{artifact}/{version}/index` returns its [LibraryIndexSummary]. [register] is
 * called at startup for already-cached coordinates and again after each successful `fetch_library`,
 * so `resources/list` stays current without a restart (the server emits `listChanged` notifications
 * on registration).
 *
 * **Why this is a registrar with its own claim set, and not a `Server` extension that checks
 * `uri in resources` first.** Under SDK 0.15.0 that check-then-act is a race, because two of its
 * changes compose:
 *  - `FeatureRegistry.add`/`addAll` now **reject** an already-registered key
 *    (`IllegalArgumentException`); 0.14.0 silently replaced it.
 *  - `Protocol` dispatches inbound requests **concurrently** once `notifications/initialized` has
 *    arrived, so two `fetch_library` calls for one coordinate really do run at the same time.
 *
 * Both callers then observe the URI as absent and the loser's `addResources` throws — inside
 * `guarded`, which turns a fetch that fully succeeded into an `isError` tool result. Claiming the
 * URI in one atomic step removes the window; the same claim also preserves the "don't re-notify
 * `listChanged` on a warm re-fetch" behaviour for free.
 * `LibraryResourcesTest.concurrentRegistrationOfOneCoordinateRegistersItOnce` pins this, and fails
 * against the membership-check version.
 *
 * One instance per [Server] — the claim set is what that server has registered.
 */
class LibraryIndexResources(private val server: Server, private val service: LibraryService) {

    /**
     * URIs this registrar has claimed. The claim — not a membership test against
     * [Server.resources] — is what makes [register] safe to call concurrently; see the class KDoc.
     */
    private val claimed = ConcurrentHashMap.newKeySet<String>()

    fun register(coordinate: LibraryCoordinate) {
        val uri = libraryIndexUri(coordinate)
        // `add` returns false for the loser of a race and for a warm re-fetch alike: both mean the
        // resource is already registered (or is being registered right now), so don't re-notify.
        if (!claimed.add(uri)) return
        // Registered as a RegisteredResource rather than via addResource(uri, …): that overload has
        // no `icons` parameter (SEP-973) and the SDK offers no addResource(Resource, handler).
        // `addAll` fires the same listChanged listeners as `add`, so resources/list stays live.
        //
        // Every cached library repeats the same glyph, so resources/list grows by ~830 B per entry.
        // Kept deliberately: resources/list — not templates/list — is what a client renders in its
        // resource picker, and it is an on-demand call, unlike the always-sent tools/list.
        server.addResources(
            listOf(
                RegisteredResource(
                    Resource(
                        uri = uri,
                        name = "$coordinate index",
                        description = "Summary of $coordinate: KMP targets, declaration/file/package counts, " +
                            "and its packages, each of whose public API is readable at " +
                            "${libraryPackageUri(coordinate, "{package}")}.",
                        mimeType = "application/json",
                        icons = Glyph.Index.icons,
                    ),
                ) { request ->
                    resourceSpan(request.uri, sessionId, request.params.meta) {
                        readIndexSummary(service, coordinate, request.uri)
                    }
                }
            )
        )
    }
}
