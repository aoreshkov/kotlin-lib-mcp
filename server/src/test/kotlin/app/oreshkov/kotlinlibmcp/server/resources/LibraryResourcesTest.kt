package app.oreshkov.kotlinlibmcp.server.resources

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.server.fakeService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LibraryResourcesTest {

    private fun serverWithResources(): Server = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                resources = ServerCapabilities.Resources(listChanged = false, subscribe = false),
            ),
            resourceTemplateMatcherFactory = segmentTemplateMatcherFactory,
        ),
    )

    @Test
    fun indexTemplateIsRegisteredWithMetadata() {
        val server = serverWithResources().apply { registerLibraryIndexTemplate(fakeService()) }
        val template = assertNotNull(
            server.resourceTemplates.find { it.uriTemplate == LIBRARY_INDEX_URI_TEMPLATE },
            "library index template not registered",
        )
        assertNotNull(template.description)
        assertEquals("application/json", template.mimeType)
        assertEquals(Glyph.Index.icons, template.icons, "SEP-973 icons")
    }

    @Test
    fun indexResourceIsRegisteredWithMetadata() {
        // Registered through `addResources(RegisteredResource(…))` because `addResource(uri, …)`
        // cannot carry icons — so assert the whole metadata set survived that detour.
        val coordinate = LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1")
        val server = serverWithResources().apply { addLibraryIndexResource(fakeService(), coordinate) }
        val resource = assertNotNull(
            server.resources[libraryIndexUri(coordinate)]?.resource,
            "library index resource not registered",
        )
        assertNotNull(resource.description)
        assertEquals("application/json", resource.mimeType)
        assertEquals(Glyph.Index.icons, resource.icons, "SEP-973 icons")
    }

    @Test
    fun matcherExtractsCoordinateFromIndexUri() {
        val coordinate = LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1")
        val matcher = segmentTemplateMatcherFactory.create(
            ResourceTemplate(uriTemplate = LIBRARY_INDEX_URI_TEMPLATE, name = "t"),
        )
        val match = assertNotNull(matcher.match(libraryIndexUri(coordinate)))
        assertEquals(
            mapOf("group" to "io.ktor", "artifact" to "ktor-client-core", "version" to "3.5.1"),
            match.variables,
        )
    }

    @Test
    fun matcherRejectsUrisWithDifferentShape() {
        val matcher = segmentTemplateMatcherFactory.create(
            ResourceTemplate(uriTemplate = LIBRARY_INDEX_URI_TEMPLATE, name = "t"),
        )
        assertNull(matcher.match("kotlinlib://io.ktor/ktor-client-core/3.5.1"), "missing /index")
        assertNull(matcher.match("kotlinlib://io.ktor/ktor-client-core/3.5.1/source"), "wrong literal")
        assertNull(matcher.match("otherscheme://io.ktor/ktor-client-core/3.5.1/index"), "wrong scheme")
    }

    @Test
    fun literalTemplateOutscoresVariableCapture() {
        val literal = segmentTemplateMatcherFactory.create(
            ResourceTemplate(uriTemplate = "kotlinlib://io.ktor/ktor-client-core/3.5.1/index", name = "t"),
        )
        val templated = segmentTemplateMatcherFactory.create(
            ResourceTemplate(uriTemplate = LIBRARY_INDEX_URI_TEMPLATE, name = "t"),
        )
        val uri = "kotlinlib://io.ktor/ktor-client-core/3.5.1/index"
        val literalScore = assertNotNull(literal.match(uri)).score
        val templatedScore = assertNotNull(templated.match(uri)).score
        assertEquals(true, literalScore > templatedScore)
    }
}
