package app.oreshkov.kotlinlibmcp.server.elicitation

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.VersionOptions
import app.oreshkov.kotlinlibmcp.server.tasks.TaskContext
import app.oreshkov.kotlinlibmcp.server.tasks.relatedTaskMeta
import app.oreshkov.kotlinlibmcp.util.MavenVersions
import co.touchlab.kermit.Logger
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestFormParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.EnumOption
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import io.modelcontextprotocol.kotlin.sdk.types.TitledSingleSelectEnumSchema
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Elicitation (2025-11-25) for the one genuinely ambiguous input this server takes: a coordinate
 * with no version.
 *
 * Without it, `fetch_library("io.ktor:ktor-client-core")` silently picks the latest stable release
 * and the user never learns which one, nor gets to say "actually, 3.4.x". With it, the client shows
 * a version picker — but only if the client said it could, and only when there is a real choice to
 * make. A client that never advertised `elicitation` sees byte-for-byte today's behavior.
 *
 * **Form mode only, deliberately.** URL mode exists for interactions that must not pass through the
 * client (credentials, payment, third-party OAuth). Nothing here is remotely sensitive — it is a
 * public Maven version number — so form mode is the correct and less invasive choice, and no field
 * ever carries a URL (the spec's SHOULD NOT for form fields).
 *
 * **Everything about elicitation lives in this file on purpose.** The draft 2026-07-28 spec
 * replaces this nested request/response shape with the multi-round-trip pattern (the server returns
 * an `InputRequiredResult` and the client *retries* the call carrying `inputResponses`), and moves
 * client capabilities into per-request `_meta`. When the SDK grows those types, this file is the
 * only thing that has to change.
 */

private val log = Logger.withTag("VersionElicitation")

/** The single schema property the picker asks for; also the key read back out of [ElicitResult]. */
private const val VERSION_FIELD = "version"

/**
 * Resolves [group]/[artifact]/[versionSpec] into a concrete coordinate, asking the user which
 * version they meant when the request left it open and the client can render the question.
 *
 * Falls through to [LibraryService.resolveCoordinate] — today's silent latest-stable pick —
 * whenever asking is impossible or pointless:
 *  - the caller named a concrete version, so there is nothing to ask;
 *  - the client did not advertise form-mode elicitation;
 *  - only one version exists, making a one-option picker pure friction.
 *
 * @throws VersionSelectionDismissedException when the user dismissed the picker.
 */
suspend fun ClientConnection.resolveCoordinate(
    server: Server,
    service: LibraryService,
    group: String,
    artifact: String,
    versionSpec: String?,
): LibraryCoordinate = resolveCoordinate(
    // The one thing the ClientConnection cannot answer for itself; see [supportsForm].
    canElicit = server.sessions[sessionId]?.clientCapabilities?.elicitation.supportsForm,
    service = service,
    group = group,
    artifact = artifact,
    versionSpec = versionSpec,
)

/** [resolveCoordinate] with the capability already decided — the seam the tests drive. */
internal suspend fun ClientConnection.resolveCoordinate(
    canElicit: Boolean,
    service: LibraryService,
    group: String,
    artifact: String,
    versionSpec: String?,
): LibraryCoordinate {
    if (!versionSpec.isOpenEnded() || !canElicit) {
        return service.resolveCoordinate(group, artifact, versionSpec)
    }
    val options = service.versionOptions(group, artifact)
    if (options.candidates.size < 2) return LibraryCoordinate(group, artifact, options.default)

    val chosen = when (val result = ask(group, artifact, options)) {
        null -> options.default // the client could not answer; not a reason to fail the fetch
        else -> when (result.action) {
            // "Don't ask me, just pick one" — honor the same default the silent path would have.
            ElicitResult.Action.Decline -> {
                log.i { "Version selection declined for $group:$artifact; using ${options.default}" }
                options.default
            }
            // Dismissed, timed out, or the client gave up. Nothing was consented to, so nothing is
            // downloaded; the model is told how to ask again unambiguously.
            ElicitResult.Action.Cancel -> throw VersionSelectionDismissedException(group, artifact)
            ElicitResult.Action.Accept -> result.selectedVersion(options)
        }
    }
    return LibraryCoordinate(group, artifact, chosen)
}

/** Raised when the user dismissed the version picker; surfaced by `guarded` as a tool error. */
class VersionSelectionDismissedException(group: String, artifact: String) : Exception(
    "Version selection for $group:$artifact was dismissed, so nothing was fetched. Call " +
        "fetch_library again with an explicit 'group:artifact:version' to skip the prompt."
)

// --- internals ---

/** A version-less coordinate, or the symbolic `latest` — the two forms that leave a choice open. */
private fun String?.isOpenEnded(): Boolean = this == null || equals("latest", ignoreCase = true)

/**
 * Whether the client can render a **form** elicitation — the counterpart of the SDK's own
 * `ClientCapabilities.Elicitation?.supportsUrl`, which covers only the other mode.
 *
 * `false` when the client declared no elicitation capability at all, or declared `url` mode alone:
 * servers **MUST NOT** send a mode the client did not declare. An entirely empty `elicitation`
 * object means form only, per the spec's backwards-compatibility rule.
 *
 * Reaching the capability is itself awkward: it lives on `ServerSession`, while the
 * `ClientConnection` a tool handler runs against exposes only its `sessionId` — hence the
 * `server.sessions[sessionId]` hop at the single call site above.
 */
internal val ClientCapabilities.Elicitation?.supportsForm: Boolean
    get() = this != null && (form != null || url == null)

/**
 * Sends the picker and returns the user's answer, or `null` if the client could not be asked.
 *
 * A broken elicitation is not a broken fetch: a client that errors, closes mid-question or does not
 * really implement the capability it advertised gets the silent default rather than a failed tool
 * call. Cancellation still propagates — that is the client abandoning the whole `tools/call`.
 */
private suspend fun ClientConnection.ask(
    group: String,
    artifact: String,
    options: VersionOptions,
): ElicitResult? {
    // A task-augmented call must park in `input_required` while it waits, and the request itself
    // has to name the task it belongs to. Absent a TaskContext this is a plain synchronous call.
    val task = coroutineContext[TaskContext]
    val request = versionRequest(group, artifact, options, task?.taskId)
    return try {
        if (task == null) {
            createElicitation(request)
        } else {
            task.awaitingInput("Waiting for a version selection for $group:$artifact") {
                createElicitation(request)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w { "Could not ask the client to pick a version for $group:$artifact (${e.message}); using ${options.default}" }
        null
    }
}

/**
 * The `elicitation/create` request: one single-select field, SEP-1330's titled form — `oneOf` with
 * `{const, title}` options, *not* the deprecated `enumNames` array.
 *
 * `default` is set so schema-aware clients pre-select the version the silent path would have
 * chosen, which makes the common answer one keystroke.
 */
private fun versionRequest(
    group: String,
    artifact: String,
    options: VersionOptions,
    taskId: String?,
): ElicitRequest = ElicitRequest(
    ElicitRequestFormParams(
        message = "$group:$artifact has ${options.candidates.size} recent versions. " +
            "Pick the one to download and analyze, or decline to use the latest stable release (${options.default}).",
        requestedSchema = ElicitRequestParams.RequestedSchema(
            properties = mapOf(
                VERSION_FIELD to TitledSingleSelectEnumSchema(
                    title = "Version",
                    description = "Which published version of $artifact to fetch",
                    oneOf = options.candidates.map { EnumOption(const = it, title = it.label(options.default)) },
                    default = options.default,
                )
            ),
            required = listOf(VERSION_FIELD),
        ),
        meta = taskId?.let { RequestMeta(relatedTaskMeta(it)) },
    )
)

/** Display label for one option: says which is the safe default and which are not releases. */
private fun String.label(default: String): String = when {
    this == default -> "$this — latest stable"
    !MavenVersions.isStable(this) -> "$this — pre-release"
    else -> this
}

/**
 * The accepted version, validated against what was actually offered.
 *
 * The spec says servers SHOULD validate that the response matches the requested schema, and this is
 * the whole of ours: the value reaches a Maven URL path, so anything not from our own `oneOf` list
 * is discarded in favor of the default rather than trusted.
 */
private fun ElicitResult.selectedVersion(options: VersionOptions): String {
    val answer = (content?.get(VERSION_FIELD) as? JsonPrimitive)?.contentOrNull
    return when (answer) {
        in options.candidates -> answer!!
        else -> {
            log.w { "Client accepted the version picker with an unoffered value '$answer'; using ${options.default}" }
            options.default
        }
    }
}
