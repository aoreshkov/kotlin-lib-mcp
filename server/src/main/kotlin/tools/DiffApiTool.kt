package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.ApiChangeType
import app.oreshkov.kotlinlibmcp.dto.ApiDiff
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Server.registerDiffApiTool(service: LibraryService) {
    addTool(
        name = "diff_api",
        description = "Compare the public API of two already-fetched versions of one artifact: " +
            "declarations added, removed, changed (same name, new signature) and deprecated, in one " +
            "call. Call fetch_library for BOTH versions first. Summary counts " +
            "(added/removed/changed/deprecated) describe the whole comparison; 'changes' is a bounded " +
            "page of it ('truncated: true' when more matched — advance 'offset'). A removed or " +
            "deprecated declaration carries 'deprecation', whose 'replaceWith' is the author's own " +
            "migration. Only public and protected declarations count, inside public classes. Overloads " +
            "are matched by signature, so changing several at once reads as removed + added. " +
            "'approximate: true' marks a change read from unresolved declaration text, which may be " +
            "formatting alone. Not modelled: opt-in markers (@RequiresOptIn) and @PublishedApi; " +
            "binary-only changes are invisible to source analysis. For the source text of a change, " +
            "use diff_versions.",
        inputSchema = ToolSchema(
            schema = JSON_SCHEMA_DIALECT,
            properties = buildJsonObject {
                put("group", stringProp("Maven group id, e.g. 'io.ktor'"))
                put("artifact", stringProp("Maven artifact id, e.g. 'ktor-client-core'"))
                put("fromVersion", stringProp("Baseline version, e.g. '3.4.0'"))
                put("toVersion", stringProp("Version to compare against the baseline, e.g. '3.5.1'"))
                put("package", stringProp("Only declarations in this package, e.g. 'io.ktor.client'"))
                put(
                    "change",
                    enumProp(
                        "Only changes of this type: 'added', 'removed', 'changed' or 'deprecated' " +
                            "(default: all). Summary counts still cover every type.",
                        values = CHANGE_TYPES,
                    ),
                )
                put(
                    "maxResults",
                    intProp(
                        "Changes per page, 1-500 (default 100)",
                        minimum = 1,
                        maximum = LibraryService.MAX_API_DIFF_RESULTS,
                        default = LibraryService.DEFAULT_API_DIFF_RESULTS,
                    ),
                )
                put(
                    "offset",
                    intProp("Number of matching changes to skip for paging (default 0)", minimum = 0, default = 0),
                )
            },
            required = listOf("group", "artifact", "fromVersion", "toVersion"),
        ),
        title = "Diff public API",
        outputSchema = outputSchemaOf<ApiDiff>(),
        toolAnnotations = LOCAL_READ_ONLY,
        icon = Glyph.ApiDiff,
    ) { request ->
        guarded(request, revealInternalErrors = service.exposeLocalPaths) {
            val args = request.args()
            toolResult(
                service.diffApi(
                    group = args.requireStringArg("group"),
                    artifact = args.requireStringArg("artifact"),
                    fromVersion = args.requireStringArg("fromVersion"),
                    toVersion = args.requireStringArg("toVersion"),
                    packageName = args.stringArg("package"),
                    change = args.stringArg("change")?.let(::changeType),
                    maxResults = args.intArg("maxResults") ?: LibraryService.DEFAULT_API_DIFF_RESULTS,
                    offset = args.intArg("offset") ?: 0,
                )
            )
        }
    }
}

/** The wire names of [ApiChangeType], read from its serializer so the schema cannot drift from them. */
private val CHANGE_TYPES: List<String> =
    ApiChangeType.serializer().descriptor.let { descriptor -> List(descriptor.elementsCount, descriptor::getElementName) }

private fun changeType(value: String): ApiChangeType =
    CHANGE_TYPES.indexOf(value.lowercase()).takeIf { it >= 0 }?.let(ApiChangeType.entries::get)
        ?: throw IllegalArgumentException("change must be one of: ${CHANGE_TYPES.joinToString()}")
