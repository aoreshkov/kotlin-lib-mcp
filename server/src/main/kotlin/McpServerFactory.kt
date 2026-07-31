package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.analyze.AnalysisApiSourceAnalyzer
import app.oreshkov.kotlinlibmcp.cache.OnDiskLibraryCache
import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.fetch.MavenSourceFetcherImpl
import app.oreshkov.kotlinlibmcp.server.completions.registerLibraryCompletions
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import app.oreshkov.kotlinlibmcp.server.prompts.registerExplainPublicApiPrompt
import app.oreshkov.kotlinlibmcp.server.resources.addLibraryIndexResource
import app.oreshkov.kotlinlibmcp.server.resources.registerLibraryIndexTemplate
import app.oreshkov.kotlinlibmcp.server.resources.segmentTemplateMatcherFactory
import app.oreshkov.kotlinlibmcp.server.tasks.TaskRecordStore
import app.oreshkov.kotlinlibmcp.server.tasks.TaskStore
import app.oreshkov.kotlinlibmcp.server.telemetry.startTelemetry
import app.oreshkov.kotlinlibmcp.server.telemetry.stopTelemetry
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
import io.opentelemetry.sdk.OpenTelemetrySdk
import java.io.Closeable
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

const val SERVER_NAME: String = "kotlin-lib-mcp"

/**
 * How the server introduces itself in `initialize`: the programmatic [SERVER_NAME], the build's
 * version, and the display branding clients show in a connector list — `title`, `websiteUrl` and
 * the SEP-973 `icons`. Kept in step with `server.json`, which carries the same three for the MCP
 * registry.
 */
internal fun serverInfo(): Implementation = Implementation(
    name = SERVER_NAME,
    version = ServerVersion.value,
    title = "Kotlin Library Sources",
    websiteUrl = "https://github.com/aoreshkov/kotlin-lib-mcp",
    icons = Glyph.Server.icons,
)

/**
 * The capabilities the server advertises. `tools`/`resources`/`prompts`/`completions` are always
 * on; `logging` is advertised only when [forwardLogsToClient] is set, and `tasks` only when [tasks]
 * is.
 *
 * `logging` is deprecated in the 2026 direction, and 2025-11-25 blesses stderr for *all* stdio
 * logging — so stderr (via `logback.xml`) is the primary observability channel and we advertise
 * `notifications/message` only when the operator opts in with `--forward-logs-to-client`. Presence
 * of any non-null value advertises a capability; the SDK then handles `logging/setLevel` per session.
 *
 * `tasks` must stay off unless the `tasks/…` handlers were actually installed for the session (see
 * `registerTaskHandlers` / `installTaskHandlersOnEverySession`): the SDK gates those methods on
 * this capability, so advertising it without the handlers would promise a surface that answers
 * nothing. Both are driven by [ServerConfig.tasks], so the two cannot disagree.
 */
internal fun serverCapabilities(
    forwardLogsToClient: Boolean,
    tasks: Boolean = false,
): ServerCapabilities = ServerCapabilities(
    tools = ServerCapabilities.Tools(listChanged = false),
    resources = ServerCapabilities.Resources(listChanged = true, subscribe = false),
    prompts = ServerCapabilities.Prompts(listChanged = false),
    // Advertises that the server answers completion/complete for prompt args and template variables.
    completions = EmptyJsonObject,
    logging = EmptyJsonObject.takeIf { forwardLogsToClient },
    tasks = if (tasks) {
        ServerCapabilities.Tasks(
            list = EmptyJsonObject,
            cancel = EmptyJsonObject,
            // The only task-augmentable server-side request category we implement.
            requests = ServerCapabilities.Tasks.Requests(
                tools = ServerCapabilities.Tasks.Requests.Tools(call = EmptyJsonObject),
            ),
        )
    } else {
        null
    },
)

/** Runtime configuration shared by both transports, populated from the CLI flags in `Main`. */
data class ServerConfig(
    val cacheDir: Path = OnDiskLibraryCache.defaultCacheRoot(),
    val repos: List<String> = emptyList(),
    /**
     * Opt into mirroring the app's logs to MCP clients as `notifications/message` (the deprecated
     * `logging` capability). Off by default: 2025-11-25 blesses stderr for *all* stdio logging, so
     * stderr (via `logback.xml`) is the primary observability channel and the capability is not
     * advertised unless this is set. See [attachMcpLogForwarder].
     */
    val forwardLogsToClient: Boolean = false,
    /**
     * Opt into exporting traces over OTLP/HTTP (`--otel`). Off by default, and off means inert:
     * no SDK, no exporter threads, no network. Endpoint, headers and resource attributes come from
     * the standard `OTEL_*` environment variables. See `telemetry/Telemetry.kt`.
     */
    val otel: Boolean = false,
    /**
     * Opt into task-augmented `tools/call` (SEP-1686) for tools that declare `taskSupport` —
     * `fetch_library` today (`--tasks`). Off by default while the extension is young: on, the
     * server advertises the `tasks` capability and answers `tasks/get`/`result`/`list`/`cancel`.
     *
     * Supported on both transports. Task records are per-process and owned by the session that
     * created them (see `tasks/TaskStore.kt`), so on HTTP each connection sees only its own.
     */
    val tasks: Boolean = false,
    /**
     * The transport being run (`stdio` or `http`), used for the `network.transport` span attribute.
     * Only meaningful when [otel] is set.
     */
    val transport: String = "stdio",
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
    private val logForwarderScope: CoroutineScope?,
    private val otelSdk: OpenTelemetrySdk? = null,
    val taskStore: TaskStore? = null,
) : Closeable {
    override fun close() {
        logForwarderScope?.cancel()
        routeKermitToSlf4j() // drop the forwarder writer (if any) for the closed server
        // Before the fetcher's client goes away, so in-flight downloads unwind rather than failing
        // on a closed transport.
        taskStore?.close()
        // Flush spans first, while the process is still healthy and the exporter can reach the
        // collector; the shutdown is bounded so an unreachable one cannot hold up exit.
        otelSdk?.let { stopTelemetry(it) }
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
        // Before any handler can run, so the very first request is traced.
        val otelSdk = if (config.otel) startTelemetry(config.transport) else null
        val cache = OnDiskLibraryCache(config.cacheDir)
        val fetcher = MavenSourceFetcherImpl(cacheDir = config.cacheDir)
        val service = LibraryService(
            fetcher = fetcher,
            analyzer = AnalysisApiSourceAnalyzer(),
            cache = cache,
            repos = config.repos,
        )

        val server = Server(
            serverInfo = serverInfo(),
            options = ServerOptions(
                capabilities = serverCapabilities(
                    forwardLogsToClient = config.forwardLogsToClient,
                    tasks = config.tasks,
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
            // Autocomplete prompt args and template variables (group/artifact/version, coordinate,
            // package) from the cache — reads only, no network.
            registerLibraryCompletions(cache)
        }
        // One index resource per already-cached library (startup snapshot).
        runBlocking { cache.list() }.forEach { server.addLibraryIndexResource(service, it) }

        // Only mirror logs to clients when opted in; otherwise stderr is the sole channel.
        val logForwarderScope = if (config.forwardLogsToClient) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).also { attachMcpLogForwarder(server, it) }
        } else {
            null
        }

        return McpServerHandle(
            server = server,
            service = service,
            cache = cache,
            fetcher = fetcher,
            logForwarderScope = logForwarderScope,
            otelSdk = otelSdk,
            // The capability above follows the same flag, so the two can never disagree.
            // Records live under the cache root so `--cache-dir` moves them like everything else.
            taskStore = if (config.tasks) {
                TaskStore(recordStore = TaskRecordStore(config.cacheDir.resolve("tasks")))
            } else {
                null
            },
        )
    }
}
