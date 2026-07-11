package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.analyze.AnalysisApiSourceAnalyzer
import app.oreshkov.kotlinlibmcp.cache.OnDiskLibraryCache
import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.fetch.MavenSourceFetcherImpl
import app.oreshkov.kotlinlibmcp.server.prompts.registerExplainPublicApiPrompt
import app.oreshkov.kotlinlibmcp.server.resources.addLibraryIndexResource
import app.oreshkov.kotlinlibmcp.server.resources.registerLibraryIndexTemplate
import app.oreshkov.kotlinlibmcp.server.resources.segmentTemplateMatcherFactory
import app.oreshkov.kotlinlibmcp.server.tools.registerFetchLibraryTool
import app.oreshkov.kotlinlibmcp.server.tools.registerGetApiSignatureTool
import app.oreshkov.kotlinlibmcp.server.tools.registerGetDependenciesTool
import app.oreshkov.kotlinlibmcp.server.tools.registerGetKDocTool
import app.oreshkov.kotlinlibmcp.server.tools.registerGetLatestVersionTool
import app.oreshkov.kotlinlibmcp.server.tools.registerGetSourceTool
import app.oreshkov.kotlinlibmcp.server.tools.registerListDeclarationsTool
import app.oreshkov.kotlinlibmcp.server.tools.registerListPackagesTool
import app.oreshkov.kotlinlibmcp.server.tools.registerListVersionsTool
import app.oreshkov.kotlinlibmcp.server.tools.registerSearchSourceTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.io.Closeable
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

const val SERVER_NAME: String = "kotlin-lib-mcp"
const val SERVER_VERSION: String = "0.1.0"

/** Runtime configuration shared by both transports, populated from the CLI flags in `Main`. */
data class ServerConfig(
    val cacheDir: Path = OnDiskLibraryCache.defaultCacheRoot(),
    val repos: List<String> = emptyList(),
)

/**
 * A configured MCP [server] plus the core collaborators it was built from. The [service] and
 * [cache] are exposed for embedders (the Compose dashboard drives fetches and browses the cache
 * through them); [close] releases the fetcher's HTTP client.
 */
class McpServerHandle(
    val server: Server,
    val service: LibraryService,
    val cache: LibraryCache,
    private val fetcher: MavenSourceFetcherImpl,
    private val logForwarderScope: CoroutineScope,
) : Closeable {
    override fun close() {
        logForwarderScope.cancel()
        routeKermitToSlf4j() // drop the forwarder writer for the closed server
        fetcher.close()
    }
}

/**
 * Composition root: constructs the `core` implementations (fetcher, analyzer, cache), builds the
 * MCP [Server], and registers every tool/resource/prompt. Both transports and the dashboard build
 * the server through here so the feature set is identical everywhere.
 */
object McpServerFactory {

    fun create(config: ServerConfig = ServerConfig()): McpServerHandle {
        routeKermitToSlf4j()
        val cache = OnDiskLibraryCache(config.cacheDir)
        val fetcher = MavenSourceFetcherImpl(cacheDir = config.cacheDir)
        val service = LibraryService(
            fetcher = fetcher,
            analyzer = AnalysisApiSourceAnalyzer(),
            cache = cache,
            repos = config.repos,
        )

        val server = Server(
            serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(listChanged = true, subscribe = false),
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                    // Presence (any non-null value) advertises notifications/message support;
                    // the SDK then handles logging/setLevel per session.
                    logging = EmptyJsonObject,
                ),
                // Not the SDK default matcher — see SegmentTemplateMatcher.kt for why.
                resourceTemplateMatcherFactory = segmentTemplateMatcherFactory,
            ),
            instructions = "Inspect the sources of Maven-published Kotlin/Java libraries. " +
                "Call fetch_library with a 'group:artifact:version' coordinate first (the version " +
                "may be omitted or 'latest' to fetch the latest stable release); the other tools " +
                "then read the cached index (packages, declarations, signatures, KDoc, raw source, " +
                "search, dependencies, versions). Use get_latest_version to look up the newest " +
                "version of an artifact without fetching it.",
        ) {
            registerFetchLibraryTool(service) { coordinate ->
                // Newly fetched libraries appear in resources/list without a restart.
                addLibraryIndexResource(service, coordinate)
            }
            registerListPackagesTool(service)
            registerListDeclarationsTool(service)
            registerGetApiSignatureTool(service)
            registerGetKDocTool(service)
            registerGetSourceTool(service)
            registerSearchSourceTool(service)
            registerGetDependenciesTool(service)
            registerListVersionsTool(service)
            registerGetLatestVersionTool(service)
            registerExplainPublicApiPrompt(service)
            // Direct addressing of any cached index; the per-library resources below stay for
            // discoverability via resources/list.
            registerLibraryIndexTemplate(service)
        }
        // One index resource per already-cached library (startup snapshot).
        runBlocking { cache.list() }.forEach { server.addLibraryIndexResource(service, it) }

        val logForwarderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        attachMcpLogForwarder(server, logForwarderScope)

        return McpServerHandle(
            server = server,
            service = service,
            cache = cache,
            fetcher = fetcher,
            logForwarderScope = logForwarderScope,
        )
    }
}
