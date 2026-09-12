package app.oreshkov.kotlinlibmcp.server.resources

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.server.fakeService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
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
        val server = serverWithResources().also { LibraryIndexResources(it, fakeService()).register(coordinate) }
        val resource = assertNotNull(
            server.resources[libraryIndexUri(coordinate)]?.resource,
            "library index resource not registered",
        )
        assertNotNull(resource.description)
        assertEquals("application/json", resource.mimeType)
        assertEquals(Glyph.Index.icons, resource.icons, "SEP-973 icons")
    }

    @Test
    fun concurrentRegistrationOfOneCoordinateRegistersItOnce() {
        // SDK 0.15.0 composes two changes that make a check-then-act registration unsafe:
        // FeatureRegistry.add/addAll now *reject* an already-registered key (0.14.0 replaced it),
        // and inbound handlers dispatch concurrently once the session is initialized. Two
        // overlapping fetch_library calls for one coordinate therefore both observe the URI as
        // absent and the loser's addResources throws — turning a successful fetch into an
        // isError tool result. Registration has to claim the URI atomically, not test for it.
        val coordinate = LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1")
        val server = serverWithResources()
        val indexResources = LibraryIndexResources(server, fakeService())
        val workers = 16
        val barrier = CyclicBarrier(workers)
        val failures = CopyOnWriteArrayList<Throwable>()

        val threads = List(workers) {
            thread {
                // Release every thread into the check/act window at once, so the interleaving the
                // race needs is actually produced rather than merely possible.
                barrier.await()
                runCatching { indexResources.register(coordinate) }
                    .onFailure { failures += it }
            }
        }
        threads.forEach { it.join() }

        assertEquals(
            emptyList(),
            failures.map { "${it::class.simpleName}: ${it.message}" },
            "concurrent registration of one coordinate must not throw",
        )
        assertEquals(
            1,
            server.resources.keys.count { it == libraryIndexUri(coordinate) },
            "the index resource must be registered exactly once",
        )
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
