package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.SourceResult
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetSourceTool(service: LibraryService) {
    addTool(
        name = "get_source",
        description = "Raw source of a whole file (by 'path', as returned by other tools, e.g. " +
            "'jvm/io/ktor/client/HttpClient.kt') or of a single declaration (by 'fqName'). " +
            "Provide exactly one of the two. Results are paged ('truncated: true' with a " +
            "'totalLines' when the file or declaration is longer than the returned page; advance " +
            "'startLine' to read on).",
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "path" to stringProp("Source file path relative to the extracted sources root"),
                "fqName" to stringProp("Fully-qualified declaration name to slice out of its file"),
                "maxLines" to intProp("Page size in lines, 1-5000 (default 500)"),
                "startLine" to intProp(
                    "1-based line to start at, absolute in the file (default: line 1, or the " +
                        "declaration's first line when using 'fqName')"
                ),
            ),
        ),
        title = "Get source",
        outputSchema = outputSchemaOf<SourceResult>(),
        toolAnnotations = LOCAL_READ_ONLY,
        icon = Glyph.Source,
    ) { request ->
        guarded(request) {
            val args = request.args()
            toolResult(
                service.getSource(
                    coordinate = args.coordinateArg(),
                    path = args.stringArg("path"),
                    fqName = args.stringArg("fqName"),
                    maxLines = args.intArg("maxLines") ?: 500,
                    startLine = args.intArg("startLine"),
                )
            )
        }
    }
}
