package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.SearchResults
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerSearchSourceTool(service: LibraryService) {
    addTool(
        name = "search_source",
        description = "Search a fetched library's sources line by line and return file:line hits " +
            "with a snippet. Substring match by default; set 'regex' for Kotlin regex syntax. " +
            "Results are capped ('truncated: true' when more existed)." + THIRD_PARTY_TEXT_NOTE,
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "query" to stringProp("Substring (default) or regex to search for"),
                "regex" to boolProp("Treat 'query' as a regular expression (default false)", default = false),
                "maxResults" to intProp(
                    "Result cap, 1-200 (default 50)",
                    minimum = 1,
                    maximum = LibraryService.MAX_SEARCH_RESULTS,
                    default = LibraryService.DEFAULT_SEARCH_RESULTS,
                ),
            ),
            extraRequired = listOf("query"),
        ),
        title = "Search sources",
        outputSchema = outputSchemaOf<SearchResults>(),
        toolAnnotations = LOCAL_READ_ONLY,
        icon = Glyph.Search,
    ) { request ->
        guarded(request, revealInternalErrors = service.exposeLocalPaths) {
            val args = request.args()
            toolResult(
                service.searchSource(
                    coordinate = args.coordinateArg(),
                    query = args.requireStringArg("query"),
                    regex = args.booleanArg("regex") ?: false,
                    maxResults = args.intArg("maxResults") ?: LibraryService.DEFAULT_SEARCH_RESULTS,
                )
            )
        }
    }
}
