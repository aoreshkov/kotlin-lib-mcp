package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.LatestVersion
import app.oreshkov.kotlinlibmcp.server.LibraryService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Server.registerGetLatestVersionTool(service: LibraryService) {
    addTool(
        name = "get_latest_version",
        description = "Resolve the latest version of an artifact from the repository's " +
            "maven-metadata.xml. Accepts 'group:artifact' or a full 'group:artifact:version' " +
            "coordinate (the version part is ignored). Returns the latest stable release plus the " +
            "newest version overall (including pre-releases) and the total version count. Works " +
            "without fetch_library.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("coordinate", stringProp("Maven coordinate 'group:artifact' or 'group:artifact:version'"))
                put("includePreReleases", boolProp("Treat the newest pre-release as 'the latest' (default false)"))
            },
            required = listOf("coordinate"),
        ),
        title = "Get latest version",
        outputSchema = outputSchemaOf<LatestVersion>(),
        toolAnnotations = REPOSITORY_READ_ONLY,
    ) { request ->
        guarded {
            val args = request.args()
            val spec = args.requireStringArg("coordinate").parseCoordinateSpec()
            val includePreReleases = args.booleanArg("includePreReleases") ?: false
            toolResult(service.latestVersion(spec.group, spec.artifact, includePreReleases))
        }
    }
}
