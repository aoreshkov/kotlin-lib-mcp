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

/**
 * Exposes one MCP resource per cached library: reading
 * `kotlinlib://{group}/{artifact}/{version}/index` returns the [app.oreshkov.kotlinlibmcp.model.LibraryIndex]
 * JSON. Registered at startup for already-cached coordinates and again after each successful
 * `fetch_library`, so `resources/list` stays current without a restart (the server emits
 * `listChanged` notifications on registration).
 */
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
