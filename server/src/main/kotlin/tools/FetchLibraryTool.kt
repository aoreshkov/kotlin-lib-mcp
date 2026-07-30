package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.FetchSummary
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.server.FetchProgress
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.elicitation.resolveCoordinate
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.shared.currentRequestHandlerExtra
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.types.TaskSupport
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolExecution
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Server.registerFetchLibraryTool(
    service: LibraryService,
    onFetched: suspend (LibraryCoordinate) -> Unit = {},
) {
    // The handler runs against a ClientConnection, which cannot see the session's client
    // capabilities; the elicitation gate needs the Server to look them up. See VersionElicitation.kt.
    val server = this
    addTool(
        name = "fetch_library",
        description = "Download, extract and analyze the sources of a Maven-published Kotlin/Java " +
            "library, warming the local cache. Idempotent — call this once per coordinate before " +
            "using the other tools. The version may be omitted or set to 'latest' (e.g. " +
            "'io.ktor:ktor-client-core' or 'io.ktor:ktor-client-core:latest') to fetch the latest " +
            "stable release — clients that support elicitation may ask the user to pick a version " +
            "in that case. Returns a summary (resolved coordinate, KMP targets, file and package counts).",
        inputSchema = ToolSchema(
            schema = JSON_SCHEMA_DIALECT,
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
        icon = Glyph.Fetch,
        // The one long-running tool here (download → analyze → cache runs seconds to tens of
        // seconds), so the one worth polling as a task. `Optional`, never `Required`: clients with
        // no task support must keep calling it synchronously exactly as before. Whether the server
        // actually honors a task-augmented call depends on `--tasks`; see `tasks/TaskHandlers.kt`.
        execution = ToolExecution(taskSupport = TaskSupport.Optional),
    ) { request ->
        guarded(request) {
            val spec = request.args().requireStringArg("coordinate").parseCoordinateSpec()
            // Asks the user which version they meant when the coordinate left it open and the
            // client supports elicitation; otherwise resolves latest stable silently, as before.
            val coordinate = resolveCoordinate(server, service, spec.group, spec.artifact, spec.versionSpec)
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

/**
 * Best-effort: a dropped progress frame must never fail the fetch itself.
 *
 * Sent through the in-flight request's [currentRequestHandlerExtra] when there is one, so the
 * notification carries `relatedRequestId` and Streamable HTTP routes it onto *this* `tools/call`'s
 * SSE stream rather than the standalone one. A tool handler never has the JSON-RPC id to pass by
 * hand — the SDK puts the extra in the handler's coroutine context (0.15.0) precisely so it does
 * not have to be plumbed through every signature.
 *
 * The fallback is not padding: a task-augmented run executes on `TaskStore`'s own scope, which the
 * handler context does not reach — and correctly so, since that request was already answered with a
 * `CreateTaskResult`. Those frames are tied to their task through `_meta` instead.
 */
private suspend fun ClientConnection.sendFetchProgress(token: ProgressToken, progress: FetchProgress) {
    val frame = ProgressNotification(
        ProgressNotificationParams(
            progressToken = token,
            progress = progress.step.toDouble(),
            total = progress.totalSteps.toDouble(),
            message = progress.message,
        )
    )
    runCatching {
        when (val extra = currentRequestHandlerExtra()) {
            null -> notification(frame)
            else -> extra.sendNotification(frame)
        }
    }
}
