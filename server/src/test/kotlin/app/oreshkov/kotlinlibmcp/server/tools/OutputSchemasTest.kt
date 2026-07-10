package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.dto.DependencyResult
import app.oreshkov.kotlinlibmcp.dto.LatestVersion
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Covers descriptor-derived tool output schemas ([outputSchemaOf]) and the dual
 * text + `structuredContent` shape of [toolResult].
 */
class OutputSchemasTest {

    @Test
    fun flatDtoSchemaListsPropertiesAndOnlyNonDefaultFieldsAreRequired() {
        val schema = outputSchemaOf<LatestVersion>()

        val properties = assertNotNull(schema.properties)
        assertEquals(
            setOf("group", "artifact", "latestStable", "latest", "includedPreReleases", "totalVersions"),
            properties.keys,
        )
        assertEquals(JsonPrimitive("string"), properties.getValue("group").jsonObject["type"])
        assertEquals(JsonPrimitive("boolean"), properties.getValue("includedPreReleases").jsonObject["type"])
        assertEquals(JsonPrimitive("integer"), properties.getValue("totalVersions").jsonObject["type"])
        // Fields with Kotlin defaults may be omitted from the payload, so only these are required.
        assertEquals(listOf("group", "artifact"), schema.required)
    }

    @Test
    fun nullableFieldAllowsNull() {
        val properties = assertNotNull(outputSchemaOf<LatestVersion>().properties)

        val latestStable = properties.getValue("latestStable").jsonObject
        val anyOf = assertNotNull(latestStable["anyOf"]).jsonArray
        assertTrue(anyOf.any { it.jsonObject["type"] == JsonPrimitive("string") })
        assertTrue(anyOf.any { it.jsonObject["type"] == JsonPrimitive("null") })
    }

    @Test
    fun recursiveModelIsEmittedOnceInDefsAndReferenced() {
        val schema = outputSchemaOf<DependencyResult>()

        val defs = assertNotNull(schema.defs, "nested classes should land in \$defs")
        val node = assertNotNull(defs["DependencyNode"]).jsonObject
        assertTrue("LibraryCoordinate" in defs.keys)

        // `root` points into $defs, and the recursive `children` list points back at the same def.
        val root = assertNotNull(schema.properties).getValue("root").jsonObject
        assertEquals(JsonPrimitive("#/\$defs/DependencyNode"), root["\$ref"])
        val children = node["properties"]!!.jsonObject.getValue("children").jsonObject
        assertEquals(JsonPrimitive("array"), children["type"])
        assertEquals(JsonPrimitive("#/\$defs/DependencyNode"), children["items"]!!.jsonObject["\$ref"])

        // Enums serialize as constrained strings.
        val target = node["properties"]!!.jsonObject.getValue("target").jsonObject
        assertEquals(JsonPrimitive("string"), target["type"])
        assertTrue(assertNotNull(target["enum"]).jsonArray.isNotEmpty())
    }

    @Test
    fun toolResultCarriesMatchingTextAndStructuredContent() {
        val value = LatestVersion(
            group = "io.ktor",
            artifact = "ktor-client-core",
            latestStable = "3.5.1",
            latest = "3.6.0-beta1",
            totalVersions = 42,
        )

        val result = toolResult(value)

        val structured = assertNotNull(result.structuredContent)
        assertEquals(JsonPrimitive("io.ktor"), structured["group"])
        val text = (result.content.single() as TextContent).text
        assertEquals(structured, Json.parseToJsonElement(assertNotNull(text)).jsonObject)
    }

    @Test
    fun structuredContentValidatesAgainstTheAdvertisedSchema() {
        // Cheap structural check: every non-default field emitted by toolResult is declared in the
        // schema, and every required property is present in the payload.
        val schema = outputSchemaOf<LatestVersion>()
        val payload = assertNotNull(toolResult(LatestVersion(group = "g", artifact = "a")).structuredContent)

        val declared = assertNotNull(schema.properties).keys
        assertTrue(payload.keys.all { it in declared }, "payload keys $payload.keys must all be declared")
        assertTrue(assertNotNull(schema.required).all { it in payload.keys })
    }
}
