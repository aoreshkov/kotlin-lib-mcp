package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.DeclarationList
import app.oreshkov.kotlinlibmcp.server.LibraryService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerListDeclarationsTool(service: LibraryService) {
    addTool(
        name = "list_declarations",
        description = "List classes/interfaces/objects/functions/properties of a fetched library " +
            "with signatures and visibility. Optionally filter by package and visibility " +
            "(public [default], internal, or all).",
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "package" to stringProp("Only declarations in this package, e.g. 'io.ktor.client'"),
                "visibility" to stringProp("Visibility filter: 'public' (default), 'internal', or 'all'"),
            ),
        ),
        title = "List declarations",
        outputSchema = outputSchemaOf<DeclarationList>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.listDeclarations(
                    coordinate = args.coordinateArg(),
                    packageName = args.stringArg("package"),
                    visibility = args.stringArg("visibility"),
                )
            )
        }
    }
}
