package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.server.FakeConnection
import app.oreshkov.kotlinlibmcp.server.FakeFetcher
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.UnusedAnalyzer
import app.oreshkov.kotlinlibmcp.server.UnusedCache
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `list_versions` splices group and artifact into a repository URL, so it must refuse the segments
 * every other tool refuses — before the fetcher sees them, not after the request has gone out.
 */
class ListVersionsToolTest {

    /** The group/artifact pairs the fetcher was asked for. */
    private val requested = mutableListOf<Pair<String, String>>()

    private val fetcher = object : MavenSourceFetcher by FakeFetcher(VersionCatalog(versions = listOf("1.0.0"))) {
        override suspend fun fetchVersionCatalog(group: String, artifact: String, repos: List<String>): VersionCatalog {
            requested += group to artifact
            return VersionCatalog(versions = listOf("1.0.0"))
        }

        // Delegation would route this to FakeFetcher's own default, past the override above.
        override suspend fun listVersions(group: String, artifact: String, repos: List<String>): List<String> =
            fetchVersionCatalog(group, artifact, repos).versions
    }

    private fun server(): Server = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ) {
        registerListVersionsTool(
            LibraryService(fetcher = fetcher, analyzer = UnusedAnalyzer, cache = UnusedCache, repos = emptyList()),
        )
    }

    private suspend fun Server.listVersions(coordinate: String): CallToolResult {
        val arguments = buildJsonObject { put("coordinate", coordinate) }
        val request = CallToolRequest(CallToolRequestParams(name = "list_versions", arguments = arguments))
        return tools.getValue("list_versions").handler(FakeConnection(), request)
    }

    @Test
    fun unsafeSegmentsAreAToolErrorAndNeverReachTheFetcher() = runTest {
        val server = server()
        for (coordinate in listOf("../../x:demo", "com.example:..", "com.example?q=1:demo", "com.example:demo#frag")) {
            assertEquals(true, server.listVersions(coordinate).isError, coordinate)
        }
        assertEquals(emptyList(), requested)
    }

    @Test
    fun aValidCoordinateStillListsVersions() = runTest {
        val result = server().listVersions("com.example:demo:1.0.0")

        assertEquals(null, result.isError?.takeIf { it })
        assertEquals(listOf("com.example" to "demo"), requested)
    }
}
