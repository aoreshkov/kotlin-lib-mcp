package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.FetchSummary
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.server.FetchProgress
import app.oreshkov.kotlinlibmcp.server.LibraryService
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Server.registerFetchLibraryTool(
    service: LibraryService,
    onFetched: suspend (LibraryCoordinate) -> Unit = {},
) {
    addTool(
        name = "fetch_library",
        description = "Download, extract and analyze the sources of a Maven-published Kotlin/Java " +
            "library, warming the local cache. Idempotent — call this once per coordinate before " +
            "using the other tools. The version may be omitted or set to 'latest' (e.g. " +
            "'io.ktor:ktor-client-core' or 'io.ktor:ktor-client-core:latest') to fetch the latest " +
            "stable release. Returns a summary (resolved coordinate, KMP targets, file and package counts).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "coordinate",
                    stringProp(
                        "Maven coordinate 'group:artifact:version', e.g. 'io.ktor:ktor-client-core:3.5.1'. " +
                            "The version may be omitted or 'latest' to resolve the latest stable release."
                    ),
                )
            },
            required = listOf("coordinate"),
        ),
        title = "Fetch library sources",
        outputSchema = outputSchemaOf<FetchSummary>(),
        // Writes the local cache (additive, repeat-safe) and downloads from Maven repositories.
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    ) { request ->
        guarded {
            val spec = request.args().requireStringArg("coordinate").parseCoordinateSpec()
            val coordinate = service.resolveCoordinate(spec.group, spec.artifact, spec.versionSpec)
            // Clients opt into notifications/progress by sending a progressToken in _meta.
            val progressToken = request.params.meta?.progressToken
            val summary = service.fetchLibrary(coordinate) { progress ->
                progressToken?.let { sendFetchProgress(it, progress) }
            }
            onFetched(coordinate)
            toolResult(summary)
        }
    }
}

/** Best-effort: a dropped progress frame must never fail the fetch itself. */
private suspend fun ClientConnection.sendFetchProgress(token: ProgressToken, progress: FetchProgress) {
    runCatching {
        notification(
            ProgressNotification(
                ProgressNotificationParams(
                    progressToken = token,
                    progress = progress.step.toDouble(),
                    total = progress.totalSteps.toDouble(),
                    message = progress.message,
                )
            )
        )
    }
}
