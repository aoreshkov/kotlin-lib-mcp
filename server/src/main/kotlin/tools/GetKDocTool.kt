package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.KDocResult
import app.oreshkov.kotlinlibmcp.server.LibraryService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetKDocTool(service: LibraryService) {
    addTool(
        name = "get_kdoc",
        description = "KDoc of one declaration by fully-qualified name: summary, description, and " +
            "structured tags (@param, @return, @throws, @sample, …). 'kdoc: null' means the " +
            "declaration is undocumented.",
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "fqName" to stringProp("Fully-qualified declaration name, e.g. 'io.ktor.client.HttpClient'"),
            ),
            extraRequired = listOf("fqName"),
        ),
        title = "Get KDoc",
        outputSchema = outputSchemaOf<KDocResult>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(service.getKDoc(args.coordinateArg(), args.requireStringArg("fqName")))
        }
    }
}
