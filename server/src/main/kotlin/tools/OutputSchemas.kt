package app.oreshkov.kotlinlibmcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/*
 * Tool `outputSchema` generation. Each tool advertises the JSON Schema of the DTO it already
 * serializes, derived from the DTO's kotlinx-serialization descriptor — one source of truth, so
 * the schema and the `structuredContent` payload cannot drift apart.
 */

/** JSON Schema of [T]'s serialized form. [T] must serialize to a JSON object. */
internal inline fun <reified T> outputSchemaOf(): ToolSchema = toolSchemaFor(serializer<T>().descriptor)

@OptIn(ExperimentalSerializationApi::class)
internal fun toolSchemaFor(descriptor: SerialDescriptor): ToolSchema {
    require(descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT) {
        "Tool output must serialize to a JSON object, got '${descriptor.serialName}' (${descriptor.kind})"
    }
    val generator = SchemaGenerator()
    val properties = generator.propertiesOf(descriptor)
    val required = generator.requiredOf(descriptor)
    return ToolSchema(
        properties = properties,
        required = required.takeIf { it.isNotEmpty() },
        defs = JsonObject(generator.defs).takeIf { generator.defs.isNotEmpty() },
    )
}

/**
 * Walks serial descriptors into JSON Schema nodes. Nested classes become `$defs` entries referenced
 * by `$ref` — the `building` guard makes recursive models (e.g. `DependencyNode.children`) emit a
 * back-reference instead of recursing forever.
 */
@OptIn(ExperimentalSerializationApi::class)
private class SchemaGenerator {
    val defs = LinkedHashMap<String, JsonObject>()
    private val building = mutableSetOf<String>()

    fun propertiesOf(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        for (i in 0 until descriptor.elementsCount) {
            put(descriptor.getElementName(i), node(descriptor.getElementDescriptor(i)))
        }
    }

    /** Optional elements (those with a Kotlin default) may be omitted from the payload. */
    fun requiredOf(descriptor: SerialDescriptor): List<String> =
        (0 until descriptor.elementsCount)
            .filterNot(descriptor::isElementOptional)
            .map(descriptor::getElementName)

    private fun node(descriptor: SerialDescriptor): JsonObject {
        val schema = when (descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> typed("string")
            PrimitiveKind.BOOLEAN -> typed("boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> typed("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> typed("number")
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                put("enum", JsonArray((0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) }))
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", node(descriptor.getElementDescriptor(0)))
            }
            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", node(descriptor.getElementDescriptor(1)))
            }
            StructureKind.CLASS, StructureKind.OBJECT -> ref(descriptor)
            else -> JsonObject(emptyMap()) // contextual/polymorphic: permissive rather than wrong
        }
        return if (descriptor.isNullable) {
            buildJsonObject { put("anyOf", JsonArray(listOf(schema, typed("null")))) }
        } else {
            schema
        }
    }

    private fun ref(descriptor: SerialDescriptor): JsonObject {
        val name = descriptor.serialName.removeSuffix("?").substringAfterLast('.')
        if (name !in defs && building.add(name)) {
            defs[name] = buildJsonObject {
                put("type", "object")
                put("properties", propertiesOf(descriptor))
                val required = requiredOf(descriptor)
                if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
            }
        }
        return buildJsonObject { put("\$ref", "#/\$defs/$name") }
    }

    private fun typed(type: String): JsonObject = buildJsonObject { put("type", type) }
}
