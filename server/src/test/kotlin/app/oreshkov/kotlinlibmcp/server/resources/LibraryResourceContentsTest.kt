package app.oreshkov.kotlinlibmcp.server.resources

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.ApiSymbol
import app.oreshkov.kotlinlibmcp.model.KDoc
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.PackageInfo
import app.oreshkov.kotlinlibmcp.model.SourceFileRef
import app.oreshkov.kotlinlibmcp.model.SourceLocation
import app.oreshkov.kotlinlibmcp.model.SymbolKind
import app.oreshkov.kotlinlibmcp.model.Visibility
import app.oreshkov.kotlinlibmcp.server.FakeFetcher
import app.oreshkov.kotlinlibmcp.server.FakeTransport
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.UnusedAnalyzer
import app.oreshkov.kotlinlibmcp.server.handshake
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.toJSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the library resources return, read through a real session the way a client reads them.
 *
 * Both are bounded: a resource has no arguments to page with, and a client may attach one to a
 * conversation wholesale, so neither may grow with the library. The index summarizes and points to
 * per-package resources; each of those caps its declarations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryResourceContentsTest {

    private val coordinate = LibraryCoordinate("com.example", "big", "1.0.0")
    private val api = "com.example.api"

    /** 250 packages; [api] holds 250 public and 5 internal declarations, each with a KDoc. */
    private val index: LibraryIndex = run {
        val file = SourceFileRef(path = "common/com/example/api/Api.kt", packageName = api)
        val symbols = (1..255).associate { i ->
            val fq = "$api.Sym$i"
            fq to ApiSymbol(
                fqName = fq,
                kind = SymbolKind.CLASS,
                visibility = if (i <= 250) Visibility.PUBLIC else Visibility.INTERNAL,
                signature = "class Sym$i",
                kdoc = KDoc(summary = "Summary of Sym$i.", description = "LONG-DESCRIPTION-$i"),
                sourceRef = SourceLocation(file, line = i, offset = 0),
            )
        }
        LibraryIndex(
            coordinate = coordinate,
            packages = listOf(PackageInfo(api, 255)) + (1..249).map { PackageInfo("com.example.p$it", 0) },
            symbolsByFqName = symbols,
            files = listOf(file),
            fetchedAt = Instant.fromEpochSeconds(0),
        )
    }

    private class SingleIndexCache(private val index: LibraryIndex) : LibraryCache {
        override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? =
            index.takeIf { it.coordinate == coordinate }
        override suspend fun putIndex(index: LibraryIndex) = Unit
        override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
        override suspend fun list(): List<LibraryCoordinate> = listOf(index.coordinate)
        override suspend fun clear(coordinate: LibraryCoordinate) = Unit
        override suspend fun size(): Long = 0
    }

    private fun server(testScope: TestScope): Server {
        val service = LibraryService(
            fetcher = FakeFetcher(VersionCatalog(versions = emptyList())),
            analyzer = UnusedAnalyzer,
            cache = SingleIndexCache(index),
        )
        return Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(listChanged = false, subscribe = false),
                ),
                resourceTemplateMatcherFactory = segmentTemplateMatcherFactory,
                handlerCoroutineContext = StandardTestDispatcher(testScope.testScheduler),
            ),
        ).apply {
            registerLibraryIndexTemplate(service)
            registerLibraryPackageTemplate(service)
        }
    }

    /** Sends `resources/read` for [uri] over a fresh session and returns the transport to inspect. */
    private suspend fun TestScope.read(uri: String): FakeTransport {
        val transport = FakeTransport()
        server(this).createSession(transport)
        transport.handshake()
        transport.deliver(ReadResourceRequest(ReadResourceRequestParams(uri = uri)).toJSON().copy(id = RequestId(2L)))
        runCurrent()
        return transport
    }

    private suspend fun TestScope.readJson(uri: String): Pair<String, JsonObject> {
        val response = assertNotNull(read(uri).responseTo(2), "no resources/read response for $uri")
        val text = (assertIs<ReadResourceResult>(response.result).contents.single() as TextResourceContents).text
        return text to Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun theIndexIsABoundedSummaryThatLeadsToItsPackages() = runTest {
        val (text, summary) = readJson(libraryIndexUri(coordinate))

        assertEquals(250, summary.getValue("packageCount").jsonPrimitive.int)
        assertEquals(200, summary.getValue("packages").jsonArray.size)
        assertTrue(summary.getValue("packagesTruncated").jsonPrimitive.boolean)
        assertEquals(255, summary.getValue("declarationCount").jsonPrimitive.int)
        assertEquals(
            "kotlinlib://com.example/big/1.0.0/package/{package}",
            summary.getValue("packageUriTemplate").jsonPrimitive.content,
        )
        // The whole point: no declaration, signature or KDoc rides along.
        assertFalse("Sym1" in text, "the summary must not carry declarations")
    }

    @Test
    fun aPackageResourceListsItsPublicApiCappedWithKDocSummaries() = runTest {
        val (text, page) = readJson(libraryPackageUri(coordinate, api))

        val declarations = page.getValue("declarations").jsonArray
        assertEquals(200, declarations.size)
        assertEquals(250, page.getValue("totalCount").jsonPrimitive.int, "public only, not the 5 internal")
        assertTrue(page.getValue("truncated").jsonPrimitive.boolean)
        val first = declarations.first().jsonObject
        assertEquals("class Sym1", first.getValue("signature").jsonPrimitive.content)
        assertEquals("Summary of Sym1.", first.getValue("summary").jsonPrimitive.content)
        assertFalse("LONG-DESCRIPTION" in text, "only the KDoc summary, not its full description")
    }

    @Test
    fun aMisspeltPackageIsAnErrorNotAnEmptyPage() = runTest {
        val transport = read(libraryPackageUri(coordinate, "com.example.apo"))

        val error = assertNotNull(transport.errorTo(2), "an unknown package must not read as an empty one")
        assertTrue("No package 'com.example.apo'" in error.error.message, error.error.message)
    }

    @Test
    fun packageTemplateIsRegisteredWithMetadataAndMatchesPackageUris() = runTest {
        val templates = server(this).resourceTemplates
        val template = assertNotNull(templates.find { it.uriTemplate == LIBRARY_PACKAGE_URI_TEMPLATE })
        assertEquals("application/json", template.mimeType)
        assertEquals(Glyph.Declarations.icons, template.icons, "SEP-973 icons")

        val matcher = segmentTemplateMatcherFactory.create(
            ResourceTemplate(uriTemplate = LIBRARY_PACKAGE_URI_TEMPLATE, name = "t"),
        )
        assertEquals(
            mapOf("group" to "com.example", "artifact" to "big", "version" to "1.0.0", "package" to api),
            assertNotNull(matcher.match(libraryPackageUri(coordinate, api))).variables,
        )
    }
}
