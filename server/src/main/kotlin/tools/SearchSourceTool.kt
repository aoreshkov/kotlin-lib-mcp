package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.SearchResults
import app.oreshkov.kotlinlibmcp.server.LibraryService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerSearchSourceTool(service: LibraryService) {
    addTool(
        name = "search_source",
        description = "Search a fetched library's sources line by line and return file:line hits " +
            "with a snippet. Substring match by default; set 'regex' for Kotlin regex syntax. " +
            "Results are capped ('truncated: true' when more existed).",
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "query" to stringProp("Substring (default) or regex to search for"),
                "regex" to boolProp("Treat 'query' as a regular expression (default false)"),
                "maxResults" to intProp("Result cap, 1-200 (default 50)"),
            ),
            extraRequired = listOf("query"),
        ),
        title = "Search sources",
        outputSchema = outputSchemaOf<SearchResults>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.searchSource(
                    coordinate = args.coordinateArg(),
                    query = args.requireStringArg("query"),
                    regex = args.booleanArg("regex") ?: false,
                    maxResults = args.intArg("maxResults") ?: 50,
                )
            )
        }
    }
}
