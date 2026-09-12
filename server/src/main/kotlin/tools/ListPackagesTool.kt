package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.PackageList
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerListPackagesTool(service: LibraryService) {
    addTool(
        name = "list_packages",
        description = "List the packages discovered in a fetched library's sources, with " +
            "declaration counts and the KMP targets each package appears in. Results are paged " +
            "('truncated: true' with a 'totalCount' when the library has more than the returned " +
            "page; advance 'offset' to fetch the rest).",
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "maxResults" to intProp("Page size, 1-1000 (default 200)"),
                "offset" to intProp("Number of packages to skip for paging (default 0)"),
            ),
        ),
        title = "List packages",
        outputSchema = outputSchemaOf<PackageList>(),
        toolAnnotations = LOCAL_READ_ONLY,
        icon = Glyph.Packages,
    ) { request ->
        guarded(request) {
            val args = request.args()
            toolResult(
                service.listPackages(
                    coordinate = args.coordinateArg(),
                    maxResults = args.intArg("maxResults") ?: 200,
                    offset = args.intArg("offset") ?: 0,
                )
            )
        }
    }
}
