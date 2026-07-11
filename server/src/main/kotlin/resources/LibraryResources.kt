package app.oreshkov.kotlinlibmcp.server.resources

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.tools.toolJson
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

/** Stable, parseable URI for a cached library's index — the dashboard and clients rely on it. */
fun libraryIndexUri(coordinate: LibraryCoordinate): String =
    "kotlinlib://${coordinate.group}/${coordinate.artifact}/${coordinate.version}/index"

/** URI template matching every library index URI; see [libraryIndexUri]. */
const val LIBRARY_INDEX_URI_TEMPLATE: String = "kotlinlib://{group}/{artifact}/{version}/index"

/**
 * Registers the `kotlinlib://{group}/{artifact}/{version}/index` resource template so clients can
 * address any cached library's index directly, without first discovering it via `resources/list`.
 * Exact-URI resources registered by [addLibraryIndexResource] take priority; the template answers
 * reads for cached coordinates that (for whatever reason) lack a static registration and gives
 * uncached coordinates a "call fetch_library first" error instead of a bare resource-not-found.
 */
fun Server.registerLibraryIndexTemplate(service: LibraryService) {
    addResourceTemplate(
        uriTemplate = LIBRARY_INDEX_URI_TEMPLATE,
        name = "Library API index",
        description = "Parsed API index of a fetched library: packages, declarations with " +
            "resolved signatures and KDoc, source file list, KMP targets. The coordinate must " +
            "have been fetched with fetch_library first.",
        mimeType = "application/json",
    ) { request, variables ->
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

/**
 * Exposes one MCP resource per cached library: reading
 * `kotlinlib://{group}/{artifact}/{version}/index` returns the [app.oreshkov.kotlinlibmcp.model.LibraryIndex]
 * JSON. Registered at startup for already-cached coordinates and again after each successful
 * `fetch_library`, so `resources/list` stays current without a restart (the server emits
 * `listChanged` notifications on registration).
 */
/** Maven coordinate segments: dots, dashes, plus — but never path separators or dot-segments. */
private val COORDINATE_SEGMENT = Regex("""[A-Za-z0-9_+-][A-Za-z0-9._+-]*""")

private fun Map<String, String>.coordinateSegment(name: String): String {
    val value = getValue(name)
    require(COORDINATE_SEGMENT.matches(value)) { "Invalid $name segment in resource URI: '$value'" }
    return value
}

fun Server.addLibraryIndexResource(service: LibraryService, coordinate: LibraryCoordinate) {
    val uri = libraryIndexUri(coordinate)
    if (uri in resources) return // warm re-fetch: already registered, don't re-notify
    addResource(
        uri = uri,
        name = "$coordinate index",
        description = "Parsed API index of $coordinate: packages, declarations with resolved " +
            "signatures and KDoc, source file list, KMP targets.",
        mimeType = "application/json",
    ) { request ->
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
