package app.oreshkov.kotlinlibmcp.dashboard

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.server.McpServerFactory
import app.oreshkov.kotlinlibmcp.server.McpServerHandle
import co.touchlab.kermit.Logger
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The in-process MCP server's lifecycle state, rendered by the control bar. */
sealed interface ServerStatus {
    data object Stopped : ServerStatus
    data class Running(val endpoint: String) : ServerStatus
}

/**
 * View-model of the dashboard: `StateFlow`s the UI collects plus the actions it triggers. All
 * work runs on [scope] (background dispatcher); no fetch/analyze logic lives here — everything
 * delegates to the shared [McpServerHandle] built by `McpServerFactory`.
 *
 * The embedded server runs over Streamable HTTP: a windowed app owns its stdin/stdout, so stdio
 * (where the protocol IS stdout) is only meaningful for the headless `server` binary.
 */
class DashboardState(private val scope: CoroutineScope) {

    private val log = Logger.withTag("Dashboard")
    private val handle: McpServerHandle = McpServerFactory.create()
    private var engine: EmbeddedServer<*, *>? = null

    val serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val prewarmStatus = MutableStateFlow("")
    val cacheEntries = MutableStateFlow<List<LibraryCoordinate>>(emptyList())
    val cacheSizeBytes: StateFlow<Long> get() = _cacheSizeBytes
    private val _cacheSizeBytes = MutableStateFlow(0L)

    init {
        refreshCache()
    }

    fun startServer(port: Int) {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port) { mcpStreamableHttp { handle.server } }.start(wait = false)
        val endpoint = "http://127.0.0.1:$port/mcp"
        serverStatus.value = ServerStatus.Running(endpoint)
        log.i { "In-process MCP server listening on $endpoint" }
    }

    fun stopServer() {
        engine?.stop()
        engine = null
        serverStatus.value = ServerStatus.Stopped
        log.i { "In-process MCP server stopped" }
    }

    /** Pre-warms [coordinateText] through the same fetch→analyze→cache path `fetch_library` uses. */
    fun prewarm(coordinateText: String) {
        val coordinate = LibraryCoordinate.parseOrNull(coordinateText.trim())
        if (coordinate == null) {
            prewarmStatus.value = "Invalid coordinate (expected group:artifact:version)"
            return
        }
        prewarmStatus.value = "Fetching $coordinate …"
        scope.launch {
            runCatching { handle.service.fetchLibrary(coordinate) }
                .onSuccess { summary ->
                    prewarmStatus.value =
                        "OK: ${summary.sourceFileCount} files, ${summary.packageCount} packages" +
                            if (summary.fromCache) " (from cache)" else ""
                    refreshCache()
                }
                .onFailure { e ->
                    log.w(e) { "Pre-warm of $coordinate failed" }
                    prewarmStatus.value = "Failed: ${e.message}"
                }
        }
    }

    fun refreshCache() {
        scope.launch {
            cacheEntries.value = handle.cache.list().sortedBy(LibraryCoordinate::toString)
            _cacheSizeBytes.value = handle.cache.size()
        }
    }

    fun clear(coordinate: LibraryCoordinate) {
        scope.launch {
            handle.cache.clear(coordinate)
            refreshCache()
        }
    }

    fun clearAll() {
        scope.launch {
            handle.cache.list().forEach { handle.cache.clear(it) }
            refreshCache()
        }
    }

    fun shutdown() {
        stopServer()
        handle.close()
    }
}
