package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.PackageInfo
import app.oreshkov.kotlinlibmcp.server.FakeConnection
import app.oreshkov.kotlinlibmcp.server.FakeFetcher
import app.oreshkov.kotlinlibmcp.server.FakeTransport
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.UnusedAnalyzer
import app.oreshkov.kotlinlibmcp.server.handshake
import app.oreshkov.kotlinlibmcp.server.resources.LibraryIndexResources
import app.oreshkov.kotlinlibmcp.server.resources.libraryIndexUri
import app.oreshkov.kotlinlibmcp.server.resources.libraryPackageUri
import app.oreshkov.kotlinlibmcp.server.resources.registerLibraryPackageTemplate
import app.oreshkov.kotlinlibmcp.server.resources.segmentTemplateMatcherFactory
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.toJSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `fetch_library` and `list_packages` hand back `resource_link`s to the bounded library resources,
 * so a client can attach those instead of re-querying. A link is only worth sending if it resolves,
 * so every one is read back through `resources/read` on a real session — and the links must never
 * displace the text block every client relies on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResourceLinksTest {

    private val coordinate = LibraryCoordinate("com.example", "demo", "1.0.0")

    /** The root package first, then 30 named ones — more than a page links. */
    private val index = LibraryIndex(
        coordinate = coordinate,
        packages = listOf(PackageInfo("", 1)) + (1..30).map { PackageInfo("com.example.p%02d".format(it), 0) },
        fetchedAt = Instant.fromEpochSeconds(0),
    )

    /** Already holds [index], so `fetch_library` takes the warm path and needs no fetcher or analyzer. */
    private class WarmCache(private val index: LibraryIndex) : LibraryCache {
        override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? =
            index.takeIf { it.coordinate == coordinate }
        override suspend fun putIndex(index: LibraryIndex) = Unit
        override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
        override suspend fun list(): List<LibraryCoordinate> = listOf(index.coordinate)
        override suspend fun clear(coordinate: LibraryCoordinate) = Unit
        override suspend fun size(): Long = 0
    }

    /**
     * Wired as `McpServerFactory` wires it, minus the index *template*: without it, the index link
     * can only resolve through the static resource `fetch_library` registers, which is the claim.
     */
    private fun server(testScope: TestScope): Server {
        val service = LibraryService(
            fetcher = FakeFetcher(VersionCatalog(versions = emptyList())),
            analyzer = UnusedAnalyzer,
            cache = WarmCache(index),
        )
        val server = Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(listChanged = false, subscribe = false),
                ),
                resourceTemplateMatcherFactory = segmentTemplateMatcherFactory,
                handlerCoroutineContext = StandardTestDispatcher(testScope.testScheduler),
            ),
        )
        val indexResources = LibraryIndexResources(server, service)
        with(server) {
            registerFetchLibraryTool(service) { indexResources.register(it) }
            registerListPackagesTool(service)
            registerListDeclarationsTool(service)
            registerLibraryPackageTemplate(service)
        }
        return server
    }

    private suspend fun Server.call(tool: String, arguments: JsonObject): CallToolResult =
        tools.getValue(tool).handler(FakeConnection(), CallToolRequest(CallToolRequestParams(tool, arguments)))

    private fun coordinateArgs(offset: Int? = null, packageName: String? = null) = buildJsonObject {
        put("coordinate", coordinate.toString())
        offset?.let { put("offset", it) }
        packageName?.let { put("package", it) }
    }

    /** Reads [uri] over a fresh session of [server], returning its JSON text, or failing. */
    private suspend fun TestScope.read(server: Server, uri: String): String {
        val transport = FakeTransport()
        server.createSession(transport)
        transport.handshake()
        transport.deliver(ReadResourceRequest(ReadResourceRequestParams(uri = uri)).toJSON().copy(id = RequestId(2L)))
        runCurrent()
        val response = assertNotNull(transport.responseTo(2), "$uri did not resolve: ${transport.errorTo(2)}")
        return (assertIs<ReadResourceResult>(response.result).contents.single() as TextResourceContents).text
    }

    private val CallToolResult.links: List<ResourceLink> get() = content.filterIsInstance<ResourceLink>()

    @Test
    fun fetchLibraryLinksTheIndexItHasJustRegistered() = runTest {
        val server = server(this)

        val result = server.call("fetch_library", coordinateArgs())

        val link = result.links.single()
        assertEquals(libraryIndexUri(coordinate), link.uri)
        assertEquals("application/json", link.mimeType)
        assertTrue(link.uri in server.resources, "the link must name the resource fetch_library registered")
        assertTrue(""""packageCount":31""" in read(server, link.uri))
    }

    @Test
    fun theTextBlockStaysFirstAndWhole() = runTest {
        // A client that ignores links must lose nothing: the text is still the full payload, and it
        // still comes first, where the spec's structured-output fallback puts it.
        val result = server(this).call("fetch_library", coordinateArgs())

        val text = assertIs<TextContent>(result.content.first()).text
        assertEquals(assertNotNull(result.structuredContent).toString(), text)
        assertEquals(2, result.content.size)
    }

    @Test
    fun linksGoOutAsResourceLinkBlocks() = runTest {
        val result = server(this).call("fetch_library", coordinateArgs())

        val wire = McpJson.encodeToJsonElement(CallToolResult.serializer(), result).jsonObject
        val blocks = wire.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "resource_link"), blocks.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(JsonPrimitive(libraryIndexUri(coordinate)), blocks[1]["uri"])
        // Links are content, not data: structuredContent must still match the outputSchema.
        assertTrue("uri" !in wire.getValue("structuredContent").jsonObject.keys)
    }

    @Test
    fun listPackagesLinksTheFirstPackagesOfThePageAndEveryLinkResolves() = runTest {
        val server = server(this)

        val result = server.call("list_packages", coordinateArgs())

        // The page holds all 31; the root package is skipped (no URI can address it), then 20 named.
        val expected = (1..MAX_PACKAGE_LINKS).map { libraryPackageUri(coordinate, "com.example.p%02d".format(it)) }
        assertEquals(expected, result.links.map { it.uri })
        result.links.forEach { link ->
            assertTrue(""""packageName":"${link.name}"""" in read(server, link.uri), link.uri)
        }
    }

    @Test
    fun aShortPageLinksEachOfItsPackagesAndTheLinksFollowTheOffset() = runTest {
        val result = server(this).call("list_packages", coordinateArgs(offset = 27))

        assertEquals(
            listOf("com.example.p27", "com.example.p28", "com.example.p29", "com.example.p30"),
            result.links.map { it.name },
        )
    }

    @Test
    fun toolsWithNothingToLinkReturnTheTextBlockAlone() = runTest {
        val result = server(this).call("list_declarations", coordinateArgs(packageName = "com.example.p01"))

        assertIs<TextContent>(result.content.single())
    }
}
