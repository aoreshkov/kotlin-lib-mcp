package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/*
 * Shared plumbing for the tools, so each tool file is a declarative adapter:
 * parse args → call LibraryService → serialize a core DTO. No business logic here or in tools.
 */

/** One JSON encoder for every tool response; pretty output reads well in MCP clients. */
internal val toolJson = Json { prettyPrint = true }

/**
 * Serializes a DTO once and returns it both ways the spec recommends: human-readable JSON text
 * (for clients without structured-output support) and `structuredContent` matching the tool's
 * `outputSchema` (see [outputSchemaOf]).
 */
internal inline fun <reified T> toolResult(value: T): CallToolResult {
    val json = toolJson.encodeToJsonElement(value)
    return CallToolResult(
        content = listOf(TextContent(toolJson.encodeToString(json))),
        structuredContent = json as? JsonObject,
    )
}

// --- behavior annotations (hints surfaced in tools/list) ---

/** Reads only the local cache/index: no side effects, closed domain. */
internal val LOCAL_READ_ONLY = ToolAnnotations(readOnlyHint = true, openWorldHint = false)

/** Read-only, but queries remote Maven repositories. */
internal val REPOSITORY_READ_ONLY = ToolAnnotations(readOnlyHint = true, openWorldHint = true)

/**
 * Runs a tool body, turning expected failures (bad arguments, un-fetched coordinate, IO) into an
 * `isError` result the model can read and act on, per the MCP tool-error convention.
 */
internal suspend fun guarded(block: suspend () -> CallToolResult): CallToolResult =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CallToolResult(content = listOf(TextContent(e.message ?: e.toString())), isError = true)
    }

// --- input schema helpers ---

internal fun stringProp(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun intProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal const val COORDINATE_DESCRIPTION: String =
    "Maven coordinate 'group:artifact:version', e.g. 'io.ktor:ktor-client-core:3.5.1'"

/** Schema with the shared `coordinate` property plus [extraProps]; [extraRequired] adds to `required`. */
internal fun coordinateSchema(
    extraProps: Map<String, JsonObject> = emptyMap(),
    extraRequired: List<String> = emptyList(),
): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        put("coordinate", stringProp(COORDINATE_DESCRIPTION))
        extraProps.forEach { (name, prop) -> put(name, prop) }
    },
    required = listOf("coordinate") + extraRequired,
)

// --- argument parsing ---

internal fun CallToolRequest.args(): JsonObject = arguments ?: JsonObject(emptyMap())

internal fun JsonObject.stringArg(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.requireStringArg(name: String): String =
    stringArg(name) ?: throw IllegalArgumentException("Missing required argument '$name'")

internal fun JsonObject.intArg(name: String): Int? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

internal fun JsonObject.booleanArg(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonObject.coordinateArg(): LibraryCoordinate =
    LibraryCoordinate.parse(requireStringArg("coordinate"))

/**
 * A coordinate whose version may be omitted or symbolic (`latest`): `group`, `artifact`, and an
 * optional `versionSpec` (`null` when only `group:artifact` was given). Used by tools that accept
 * `latest`/version-less coordinates (`fetch_library`, `get_latest_version`).
 */
internal data class CoordinateSpec(val group: String, val artifact: String, val versionSpec: String?)

/** Parses `group:artifact` or `group:artifact:version` (version may be `latest`). */
internal fun String.parseCoordinateSpec(): CoordinateSpec {
    val parts = split(':')
    require(parts.size in 2..3 && parts.take(2).none { it.isBlank() }) {
        "Invalid coordinate '$this': expected 'group:artifact' or 'group:artifact:version'"
    }
    val group = parts[0].trim()
    val artifact = parts[1].trim()
    // group/artifact reach Maven-metadata URL paths (fetchVersionCatalog) before a full
    // LibraryCoordinate — which validates all three segments — is ever built, so guard them here.
    LibraryCoordinate.requireValidSegment("group", group)
    LibraryCoordinate.requireValidSegment("artifact", artifact)
    return CoordinateSpec(
        group = group,
        artifact = artifact,
        versionSpec = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
    )
}
