package app.oreshkov.kotlinlibmcp.server.resources

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
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

/** Stable, parseable URI for a cached library's index — the dashboard and clients rely on it. */
fun libraryIndexUri(coordinate: LibraryCoordinate): String =
    "kotlinlib://${coordinate.group}/${coordinate.artifact}/${coordinate.version}/index"

/** URI template matching every library index URI; see [libraryIndexUri]. */
const val LIBRARY_INDEX_URI_TEMPLATE: String = "kotlinlib://{group}/{artifact}/{version}/index"

/**
 * Registers the `kotlinlib://{group}/{artifact}/{version}/index` resource template so clients can
 * address any cached library's index directly, without first discovering it via `resources/list`.
 * Exact-URI resources registered by [LibraryIndexResources] take priority; the template answers
 * reads for cached coordinates that (for whatever reason) lack a static registration and gives
 * uncached coordinates a "call fetch_library first" error instead of a bare resource-not-found.
 */
fun Server.registerLibraryIndexTemplate(service: LibraryService) {
    addResourceTemplate(
        ResourceTemplate(
            uriTemplate = LIBRARY_INDEX_URI_TEMPLATE,
            name = "Library API index",
            description = "Parsed API index of a fetched library: packages, declarations with " +
                "resolved signatures and KDoc, source file list, KMP targets. The coordinate must " +
                "have been fetched with fetch_library first.",
            mimeType = "application/json",
            icons = Glyph.Index.icons,
        )
    ) { request, variables ->
        resourceSpan(request.uri, sessionId, request.params.meta) {
            // Template variables are attacker-controlled URI segments and end up in cache paths.
            val coordinate = LibraryCoordinate(
                group = variables.coordinateSegment("group"),
                artifact = variables.coordinateSegment("artifact"),
                version = variables.coordinateSegment("version"),
            )
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        text = toolJson.encodeToString(service.index(coordinate)),
                        uri = request.uri,
                        mimeType = "application/json",
                    )
                )
            )
        }
    }
}

/** Maven coordinate segments: dots, dashes, plus — but never path separators or dot-segments. */
private val COORDINATE_SEGMENT = Regex("""[A-Za-z0-9_+-][A-Za-z0-9._+-]*""")

private fun Map<String, String>.coordinateSegment(name: String): String {
    val value = getValue(name)
    require(COORDINATE_SEGMENT.matches(value)) { "Invalid $name segment in resource URI: '$value'" }
    return value
}

/**
 * Exposes one MCP resource per cached library: reading
 * `kotlinlib://{group}/{artifact}/{version}/index` returns the [app.oreshkov.kotlinlibmcp.model.LibraryIndex]
 * JSON. [register] is called at startup for already-cached coordinates and again after each
 * successful `fetch_library`, so `resources/list` stays current without a restart (the server emits
 * `listChanged` notifications on registration).
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
                        description = "Parsed API index of $coordinate: packages, declarations with " +
                            "resolved signatures and KDoc, source file list, KMP targets.",
                        mimeType = "application/json",
                        icons = Glyph.Index.icons,
                    ),
                ) { request ->
                    resourceSpan(request.uri, sessionId, request.params.meta) {
                        ReadResourceResult(
                            contents = listOf(
                                TextResourceContents(
                                    text = toolJson.encodeToString(service.index(coordinate)),
                                    uri = request.uri,
                                    mimeType = "application/json",
                                )
                            )
                        )
                    }
                }
            )
        )
    }
}
