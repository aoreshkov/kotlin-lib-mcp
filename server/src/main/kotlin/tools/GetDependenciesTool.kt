package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.DependencyResult
import app.oreshkov.kotlinlibmcp.server.LibraryService
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetDependenciesTool(service: LibraryService) {
    addTool(
        name = "get_dependencies",
        description = "Dependency tree of a library parsed from its .pom/.module metadata: direct " +
            "dependencies with scopes, optionally transitive to a bounded depth. Works without " +
            "fetch_library (reads repository metadata, not sources).",
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "depth" to intProp("Transitive resolution depth, 1-5 (default 1 = direct only)"),
            ),
        ),
        title = "Get dependency tree",
        outputSchema = outputSchemaOf<DependencyResult>(),
        // Read-only but open-world: resolves .pom/.module metadata from remote repositories.
        toolAnnotations = REPOSITORY_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            toolResult(service.getDependencies(args.coordinateArg(), args.intArg("depth") ?: 1))
        }
    }
}
