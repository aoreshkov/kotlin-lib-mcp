package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.VersionList
import app.oreshkov.kotlinlibmcp.server.LibraryService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Server.registerListVersionsTool(service: LibraryService) {
    addTool(
        name = "list_versions",
        description = "List the published versions of an artifact from the repository's " +
            "maven-metadata.xml. Accepts 'group:artifact' or a full 'group:artifact:version' " +
            "coordinate (the version part is ignored). Works without fetch_library.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("coordinate", stringProp("Maven coordinate 'group:artifact' or 'group:artifact:version'"))
            },
            required = listOf("coordinate"),
        ),
        title = "List versions",
        outputSchema = outputSchemaOf<VersionList>(),
        toolAnnotations = REPOSITORY_READ_ONLY,
    ) { request ->
        guarded {
            val parts = request.args().requireStringArg("coordinate").split(':')
            require(parts.size in 2..3 && parts.take(2).none(String::isBlank)) {
                "Invalid coordinate '${parts.joinToString(":")}': expected 'group:artifact[:version]'"
            }
            toolResult(service.listVersions(group = parts[0].trim(), artifact = parts[1].trim()))
        }
    }
}
