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
            "Results are paged: 'totalCount' is how many lines matched and 'truncated: true' means " +
            "more matched than were returned (advance 'offset'). Counting stops at " +
            "${LibraryService.MAX_SEARCH_MATCHES_COUNTED} matches, " +
            "where 'totalCountCapped: true' marks 'totalCount' as a lower bound and the rest as " +
            "out of reach; narrow the query instead of paging that far." +
            THIRD_PARTY_TEXT_NOTE,
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "query" to stringProp("Substring (default) or regex to search for"),
                "regex" to boolProp("Treat 'query' as a regular expression (default false)", default = false),
                "maxResults" to intProp(
                    "Hits per page, 1-200 (default 50)",
                    minimum = 1,
                    maximum = LibraryService.MAX_SEARCH_RESULTS,
                    default = LibraryService.DEFAULT_SEARCH_RESULTS,
                ),
                "offset" to intProp("Number of matching lines to skip for paging (default 0)", minimum = 0, default = 0),
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
                    offset = args.intArg("offset") ?: 0,
                )
            )
        }
    }
}
