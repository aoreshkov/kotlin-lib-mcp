package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.SourceResult
import app.oreshkov.kotlinlibmcp.server.LibraryService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetSourceTool(service: LibraryService) {
    addTool(
        name = "get_source",
        description = "Raw source of a whole file (by 'path', as returned by other tools, e.g. " +
            "'jvm/io/ktor/client/HttpClient.kt') or of a single declaration (by 'fqName'). " +
            "Provide exactly one of the two.",
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "path" to stringProp("Source file path relative to the extracted sources root"),
                "fqName" to stringProp("Fully-qualified declaration name to slice out of its file"),
            ),
        ),
        title = "Get source",
        outputSchema = outputSchemaOf<SourceResult>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(
                service.getSource(
                    coordinate = args.coordinateArg(),
                    path = args.stringArg("path"),
                    fqName = args.stringArg("fqName"),
                )
            )
        }
    }
}
