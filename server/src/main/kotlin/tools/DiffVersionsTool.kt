package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.VersionDiff
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Server.registerDiffVersionsTool(service: LibraryService) {
    addTool(
        name = "diff_versions",
        description = "Compare the sources of two already-fetched versions of one artifact and " +
            "report what changed, as unified diff hunks. Call fetch_library for BOTH versions " +
            "first. Summary counts (filesAdded/filesRemoved/filesModified) describe the whole " +
            "comparison; 'files' is a bounded page of it ('truncated: true' when more matched — " +
            "advance 'offset'). Added and removed files report line counts but no hunks; read them " +
            "with get_source. Narrow a large library with 'path' before paging through it.",
        inputSchema = ToolSchema(
            schema = JSON_SCHEMA_DIALECT,
            properties = buildJsonObject {
                put("group", stringProp("Maven group id, e.g. 'io.ktor'"))
                put("artifact", stringProp("Maven artifact id, e.g. 'ktor-client-core'"))
                put("fromVersion", stringProp("Baseline version, e.g. '3.4.0'"))
                put("toVersion", stringProp("Version to compare against the baseline, e.g. '3.5.1'"))
                put(
                    "path",
                    stringProp(
                        "Only files whose path contains this, e.g. 'io/ktor/client/engine'. " +
                            "Paths have their KMP target directory stripped, so they read like " +
                            "'commonMain/io/ktor/client/HttpClient.kt'."
                    ),
                )
                put("maxResults", intProp("Files per page, 1-50 (default 20)"))
                put("offset", intProp("Number of changed files to skip for paging (default 0)"))
                put("contextLines", intProp("Unchanged lines of context around each hunk, 0-10 (default 3)"))
            },
            required = listOf("group", "artifact", "fromVersion", "toVersion"),
        ),
        title = "Diff versions",
        outputSchema = outputSchemaOf<VersionDiff>(),
        toolAnnotations = LOCAL_READ_ONLY,
        icon = Glyph.Diff,
    ) { request ->
        guarded(request) {
            val args = request.args()
            toolResult(
                service.diffVersions(
                    group = args.requireStringArg("group"),
                    artifact = args.requireStringArg("artifact"),
                    fromVersion = args.requireStringArg("fromVersion"),
                    toVersion = args.requireStringArg("toVersion"),
                    pathFilter = args.stringArg("path"),
                    maxResults = args.intArg("maxResults") ?: 20,
                    offset = args.intArg("offset") ?: 0,
                    contextLines = args.intArg("contextLines") ?: 3,
                )
            )
        }
    }
}
