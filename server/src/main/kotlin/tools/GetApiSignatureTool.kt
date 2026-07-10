package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.SignatureResult
import app.oreshkov.kotlinlibmcp.server.LibraryService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetApiSignatureTool(service: LibraryService) {
    addTool(
        name = "get_api_signature",
        description = "Full resolved signature of one declaration by fully-qualified name: type " +
            "parameters, parameters, return type, supertypes, modifiers. 'bestEffort: true' marks " +
            "signatures recovered from raw source when type resolution was incomplete.",
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "fqName" to stringProp("Fully-qualified declaration name, e.g. 'io.ktor.client.HttpClient'"),
            ),
            extraRequired = listOf("fqName"),
        ),
        title = "Get API signature",
        outputSchema = outputSchemaOf<SignatureResult>(),
        toolAnnotations = LOCAL_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(service.getSignature(args.coordinateArg(), args.requireStringArg("fqName")))
        }
    }
}
