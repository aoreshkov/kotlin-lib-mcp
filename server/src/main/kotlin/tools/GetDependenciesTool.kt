package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.DependencyResult
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerGetDependenciesTool(service: LibraryService) {
    addTool(
        name = "get_dependencies",
        description = "Dependency tree of a library parsed from its .pom/.module metadata: direct " +
            "dependencies with scopes, optionally transitive to a bounded depth. Works without " +
            "fetch_library (reads repository metadata, not sources). The returned tree is bounded " +
            "by 'maxNodes' as well as by 'depth': when 'truncated' is true the tree was pruned " +
            "breadth-first (direct dependencies kept, deepest transitives dropped) and " +
            "'totalNodes' says how large it really is.",
        inputSchema = coordinateSchema(
            extraProps = mapOf(
                "depth" to intProp("Transitive resolution depth, 1-5 (default 1 = direct only)"),
                "maxNodes" to intProp("Nodes to return, 1-1000 (default 200)"),
            ),
        ),
        title = "Get dependency tree",
        outputSchema = outputSchemaOf<DependencyResult>(),
        // Read-only but open-world: resolves .pom/.module metadata from remote repositories.
        toolAnnotations = REPOSITORY_READ_ONLY,
        icon = Glyph.Dependencies,
    ) { request ->
        guarded(request) {
            val args = request.args()
            toolResult(
                service.getDependencies(
                    coordinate = args.coordinateArg(),
                    depth = args.intArg("depth") ?: 1,
                    maxNodes = args.intArg("maxNodes") ?: 200,
                )
            )
        }
    }
}
