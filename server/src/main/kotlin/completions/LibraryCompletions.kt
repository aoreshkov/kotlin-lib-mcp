package app.oreshkov.kotlinlibmcp.server.completions

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.server.resources.LIBRARY_INDEX_URI_TEMPLATE
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequest
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CompleteResult
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.PromptReference
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplateReference

/** Prompt whose arguments we autocomplete; see [registerExplainPublicApiPrompt]. */
private const val EXPLAIN_PROMPT = "explain_public_api"

/** MCP suggests clients cap completion values at 100; we advertise the overflow via `hasMore`. */
private const val MAX_COMPLETIONS = 100

/**
 * Registers the `completion/complete` handler that autocompletes **prompt arguments** and
 * **resource-template variables** from the on-disk cache — no network. Completion in MCP applies
 * only to prompts and resource templates (never tool arguments), so the tools are untouched.
 *
 * Suggestions are:
 * - the `group`/`artifact`/`version` variables of the [LIBRARY_INDEX_URI_TEMPLATE] resource
 *   template, each narrowed by any sibling segments the client has already resolved; and
 * - the [EXPLAIN_PROMPT] prompt's `coordinate` (cached `group:artifact:version` strings) and
 *   `package` (packages of the coordinate already in context).
 *
 * The handler never throws: any failure (unparseable context, cache miss, corrupt entry) yields
 * an empty completion rather than failing the client's request.
 */
fun Server.registerLibraryCompletions(cache: LibraryCache) {
    // The SDK's Server wires tools/prompts/resources into each session but has no completion hook,
    // so — as the SDK's own conformance suite does — install the handler on each connecting session.
    onConnect {
        val session = sessions.values.lastOrNull() ?: return@onConnect
        session.setRequestHandler<CompleteRequest>(Method.Defined.CompletionComplete) { request, _ ->
            val values = runCatching {
                val ctx = request.context?.arguments.orEmpty()
                when (val ref = request.ref) {
                    is ResourceTemplateReference ->
                        if (ref.uri == LIBRARY_INDEX_URI_TEMPLATE) {
                            coordinateSegmentCompletions(request.argument, ctx, cache.list())
                        } else {
                            emptyList()
                        }
                    is PromptReference ->
                        if (ref.name == EXPLAIN_PROMPT) promptArgCompletions(request.argument, ctx, cache) else emptyList()
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())

            val capped = values.take(MAX_COMPLETIONS)
            CompleteResult(
                completion = CompleteResult.Completion(
                    values = capped,
                    total = values.size,
                    hasMore = values.size > MAX_COMPLETIONS,
                ),
            )
        }
    }
}

/**
 * Completes a `group`/`artifact`/`version` template variable from the [cached] coordinates,
 * narrowing by any sibling segments already resolved in [ctx] (e.g. completing `artifact` only
 * offers artifacts under the chosen `group`). Prefix match, case-insensitive, de-duplicated.
 */
internal fun coordinateSegmentCompletions(
    arg: CompleteRequestParams.Argument,
    ctx: Map<String, String>,
    cached: List<LibraryCoordinate>,
): List<String> {
    val candidates = when (arg.name) {
        "group" -> cached.map { it.group }
        "artifact" -> cached.filter { it.matchesCtx(ctx, group = true) }.map { it.artifact }
        "version" -> cached.filter { it.matchesCtx(ctx, group = true, artifact = true) }.map { it.version }
        else -> return emptyList()
    }
    return candidates.prefixed(arg.value)
}

/**
 * Completes the [EXPLAIN_PROMPT] prompt arguments: `coordinate` from the cached libraries as
 * `group:artifact:version`, and `package` from the packages of the coordinate carried in [ctx].
 */
internal suspend fun promptArgCompletions(
    arg: CompleteRequestParams.Argument,
    ctx: Map<String, String>,
    cache: LibraryCache,
): List<String> = when (arg.name) {
    "coordinate" -> cache.list().map { it.toString() }.prefixed(arg.value)
    "package" -> {
        val coordinate = ctx["coordinate"]?.let(LibraryCoordinate::parseOrNull)
        val packages = coordinate?.let { cache.get(it) }?.packages?.map { it.name }.orEmpty()
        packages.prefixed(arg.value)
    }
    else -> emptyList()
}

/** True when this coordinate's group/artifact match the corresponding already-resolved [ctx] value. */
private fun LibraryCoordinate.matchesCtx(
    ctx: Map<String, String>,
    group: Boolean = false,
    artifact: Boolean = false,
): Boolean =
    (!group || ctx["group"]?.let { it == this.group } ?: true) &&
        (!artifact || ctx["artifact"]?.let { it == this.artifact } ?: true)

/** Case-insensitive prefix filter, de-duplicated and sorted — the shared shape of every suggestion. */
private fun List<String>.prefixed(value: String): List<String> {
    val prefix = value.lowercase()
    return asSequence()
        .distinct()
        .filter { it.lowercase().startsWith(prefix) }
        .sorted()
        .toList()
}
