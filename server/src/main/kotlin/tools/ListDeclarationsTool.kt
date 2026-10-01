package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.DeclarationList
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerListDeclarationsTool(service: LibraryService) {
    addTool(
        name = "list_declarations",
        description = "List classes/interfaces/objects/functions/properties of a fetched library " +
            "with signatures and visibility. Optionally filter by package and visibility " +
            "(public [default], internal, or all). Results are paged ('truncated: true' with a " +
            "'totalCount' when more matched than the returned page; advance 'offset' to fetch the rest).",
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "package" to stringProp("Only declarations in this package, e.g. 'io.ktor.client'"),
                "visibility" to enumProp(
                    "Visibility filter: 'public' (default), 'internal', or 'all'",
                    values = listOf("public", "internal", "all"),
                    default = "public",
                ),
                "maxResults" to intProp(
                    "Page size, 1-500 (default 100)",
                    minimum = 1,
                    maximum = LibraryService.MAX_DECLARATION_RESULTS,
                    default = LibraryService.DEFAULT_DECLARATION_RESULTS,
                ),
                "offset" to intProp(
                    "Number of matching declarations to skip for paging (default 0)",
                    minimum = 0,
                    default = 0,
                ),
            ),
        ),
        title = "List declarations",
        outputSchema = outputSchemaOf<DeclarationList>(),
        toolAnnotations = LOCAL_READ_ONLY,
        icon = Glyph.Declarations,
    ) { request ->
        guarded(request, revealInternalErrors = service.exposeLocalPaths) {
            val args = request.args()
            toolResult(
                service.listDeclarations(
                    coordinate = args.coordinateArg(),
                    packageName = args.stringArg("package"),
                    visibility = args.stringArg("visibility"),
                    maxResults = args.intArg("maxResults") ?: LibraryService.DEFAULT_DECLARATION_RESULTS,
                    offset = args.intArg("offset") ?: 0,
                )
            )
        }
    }
}
