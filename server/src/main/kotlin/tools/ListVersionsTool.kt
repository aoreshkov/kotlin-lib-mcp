package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.VersionList
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Server.registerListVersionsTool(service: LibraryService) {
    addTool(
        name = "list_versions",
        description = "List the published versions of an artifact from the repository's " +
            "maven-metadata.xml. Accepts 'group:artifact' or a full 'group:artifact:version' " +
            "coordinate (the version part is ignored). Works without fetch_library. Newest first " +
            "and paged ('truncated: true' with a 'totalCount' when the artifact has more than the " +
            "returned page; advance 'offset' for older versions).",
        inputSchema = ToolSchema(
            schema = JSON_SCHEMA_DIALECT,
            properties = buildJsonObject {
                put("coordinate", stringProp("Maven coordinate 'group:artifact' or 'group:artifact:version'"))
                put(
                    "maxResults",
                    intProp(
                        "Page size, 1-500 (default 100)",
                        minimum = 1,
                        maximum = LibraryService.MAX_VERSION_RESULTS,
                        default = LibraryService.DEFAULT_VERSION_RESULTS,
                    ),
                )
                put("offset", intProp("Number of versions to skip for paging (default 0)", minimum = 0, default = 0))
            },
            required = listOf("coordinate"),
        ),
        title = "List versions",
        outputSchema = outputSchemaOf<VersionList>(),
        toolAnnotations = REPOSITORY_READ_ONLY,
        icon = Glyph.Versions,
    ) { request ->
        guarded(request, revealInternalErrors = service.exposeLocalPaths) {
            val args = request.args()
            val spec = args.requireStringArg("coordinate").parseCoordinateSpec()
            toolResult(
                service.listVersions(
                    group = spec.group,
                    artifact = spec.artifact,
                    maxResults = args.intArg("maxResults") ?: LibraryService.DEFAULT_VERSION_RESULTS,
                    offset = args.intArg("offset") ?: 0,
                )
            )
        }
    }
}
